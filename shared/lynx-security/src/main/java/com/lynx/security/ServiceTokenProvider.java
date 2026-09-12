package com.lynx.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Obtains and reuses a service-identity token (OAuth2 Client Credentials,
 * RFC 6749 §4.4) for one Lynx service to call another — see ADR-007's
 * "Implementation Details" section for the full design and the reasoning
 * behind each choice below.
 *
 * <p>Deliberately lives here, not per-service: this is pure mechanism, the
 * same reasoning that keeps {@link JwtVerifier} shared. Any future caller
 * (e.g. {@code saga-orchestrator} calling {@code ledger-service}, or
 * {@code ledger-service} calling a future {@code account-service}) needs
 * identical behavior, so it's built once here rather than per-service.
 *
 * <ul>
 *   <li><b>Cached, not fetched per call</b> — {@link #currentToken()}
 *       reuses the cached token until it's within {@code TOKEN_TTL_BUFFER}
 *       of expiring.
 *   <li><b>Single-flight refresh</b> — {@code currentToken()} is
 *       {@code synchronized}: concurrent callers hitting an expired cache
 *       at once block briefly and reuse the SAME refreshed token, rather
 *       than each firing an independent request at the token endpoint.
 *   <li><b>Reactive invalidation</b> — {@link #invalidate()} lets a caller
 *       force a fresh fetch after a downstream 401 the cache didn't
 *       anticipate, on top of the TTL-based expiry.
 *   <li><b>Circuit breaker, scoped to this fetch only</b> — wraps ONLY the
 *       call to the token endpoint. It has no visibility into, and no
 *       effect on, whatever the caller does with the token afterward.
 *       resilience4j — this class's own hand-rolled {@code CircuitBreaker}
 *       was retired once a second real caller ({@code saga-orchestrator}'s
 *       downstream-call breakers, other-docs/10 Decision 8) needed the
 *       dependency anyway; no {@code ignoreExceptions(...)} needed here
 *       unlike those — every {@link ServiceTokenException} this fetch can
 *       throw genuinely IS a token-endpoint failure, there's no 4xx-vs-5xx
 *       distinction to make for a token response.
 *   <li><b>In-process cache, not shared (e.g. Redis)</b> — deliberately.
 *       Refreshed roughly once per ~50 minutes PER INSTANCE, a trivial
 *       request rate; sharing would add real complexity for an
 *       unmeasurable benefit at this call frequency.
 * </ul>
 */
public final class ServiceTokenProvider {

  private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

  /** Refresh once within 10 minutes of real expiry — absorbs clock skew, never held to the wire. */
  private static final Duration TOKEN_TTL_BUFFER = Duration.ofMinutes(10);

  /**
   * 5 consecutive failures, 30s cooldown — a count-based sliding window
   * sized exactly to the threshold, with a 100% failure-rate requirement,
   * is what makes "N consecutive failures" and "N failures out of the
   * last N calls, 100% rate" equivalent: a single success anywhere in the
   * window brings the rate under 100% and the window slides forward, so
   * it can't trip on non-consecutive failures either.
   */
  private static final CircuitBreakerConfig DEFAULT_CONFIG = CircuitBreakerConfig.custom()
      .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
      .slidingWindowSize(5)
      .minimumNumberOfCalls(5)
      .failureRateThreshold(100)
      .waitDurationInOpenState(Duration.ofSeconds(30))
      .permittedNumberOfCallsInHalfOpenState(1)
      .build();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient httpClient;
  private final URI tokenEndpoint;
  private final String clientId;
  private final String clientSecret;
  private final Clock clock;
  private final CircuitBreaker circuitBreaker;

  private CachedToken cached;

  public ServiceTokenProvider(HttpClient httpClient, URI tokenEndpoint,
                               String clientId, String clientSecret) {
    this(httpClient, tokenEndpoint, clientId, clientSecret,
        Clock.systemUTC(), CircuitBreaker.of("service-token-provider", DEFAULT_CONFIG));
  }

  ServiceTokenProvider(HttpClient httpClient, URI tokenEndpoint, String clientId,
                        String clientSecret, Clock clock, CircuitBreaker circuitBreaker) {
    this.httpClient = httpClient;
    this.tokenEndpoint = tokenEndpoint;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.clock = clock;
    this.circuitBreaker = circuitBreaker;
  }

  /**
   * A valid service-identity token, ready to use as
   * {@code Authorization: Bearer <token>}. Fetches (and caches) a new one
   * only when the cached one is missing or within {@code TOKEN_TTL_BUFFER}
   * of expiring.
   *
   * @throws CallNotPermittedException if the token endpoint has failed
   *     enough times in a row and the cooldown hasn't elapsed yet — the
   *     token endpoint is never called in this case
   * @throws ServiceTokenException if the fetch itself failed (unreachable,
   *     non-200, malformed response)
   */
  public synchronized String currentToken() {
    if (cached == null || isNearExpiry(cached)) {
      cached = circuitBreaker.executeSupplier(this::fetchToken);
    }
    return cached.value();
  }

  /**
   * Forces the next {@link #currentToken()} call to fetch a fresh token,
   * regardless of the cached one's remaining TTL — call this after a
   * downstream service rejects a request with 401 despite the cache
   * believing the token was still valid.
   */
  public synchronized void invalidate() {
    log.info("Service token invalidated for {} — next call will force a fresh fetch", tokenEndpoint);
    cached = null;
  }

  private boolean isNearExpiry(CachedToken token) {
    return !clock.instant().isBefore(token.expiresAt().minus(TOKEN_TTL_BUFFER));
  }

  private CachedToken fetchToken() {
    String credentials = Base64.getEncoder().encodeToString(
        (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
        .header("Authorization", "Basic " + credentials)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(BodyPublishers.ofString("grant_type=client_credentials"))
        .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, BodyHandlers.ofString());
    } catch (Exception e) {
      log.warn("Failed to reach token endpoint {}", tokenEndpoint, e);
      throw new ServiceTokenException("Failed to reach token endpoint " + tokenEndpoint, e);
    }

    if (response.statusCode() != 200) {
      log.warn("Token endpoint {} returned {} (circuit breaker failure count incremented)",
          tokenEndpoint, response.statusCode());
      throw new ServiceTokenException(
          "Token endpoint " + tokenEndpoint + " returned " + response.statusCode()
              + ": " + response.body());
    }

    try {
      JsonNode json = MAPPER.readTree(response.body());
      String accessToken = json.get("access_token").asText();
      long expiresInSeconds = json.get("expires_in").asLong();
      // Never log accessToken itself — a credential, not diagnostic data.
      log.info("Fetched a fresh service token from {} (expires in {}s)", tokenEndpoint, expiresInSeconds);
      return new CachedToken(accessToken, clock.instant().plusSeconds(expiresInSeconds));
    } catch (Exception e) {
      log.warn("Malformed token response from {}", tokenEndpoint, e);
      throw new ServiceTokenException("Malformed token response: " + response.body(), e);
    }
  }

  private record CachedToken(String value, Instant expiresAt) {
  }
}
