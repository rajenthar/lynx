package com.lynx.orchestrator.config;

import com.lynx.orchestrator.client.FxRateServiceClient;
import com.lynx.orchestrator.client.LedgerServiceClient;
import com.lynx.security.JwtVerifier;
import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wiring for this service's cross-cutting beans — {@link JwtVerifier} for
 * verifying an INCOMING caller (same as every other service), and {@link
 * ServiceTokenProvider} for authenticating THIS service's own OUTGOING
 * calls to {@code ledger-service}/{@code fx-rate-service} (ADR-007). One
 * {@code ServiceTokenProvider} instance, shared by both downstream
 * clients — a single service-identity token authenticates this service to
 * every OTHER service identically, there's no reason for two.
 *
 * <p>Each downstream client also gets its OWN resilience4j {@link
 * CircuitBreaker} instance (other-docs/10 Decision 8) — deliberately NOT
 * the same breaker {@link ServiceTokenProvider} uses internally for its
 * own token fetch (a separate, independently-named resilience4j breaker
 * inside that class — see its own javadoc; {@code lynx-security} used to
 * have its own hand-rolled {@code CircuitBreaker} for this instead, since
 * retired once resilience4j was needed here anyway, one implementation
 * everywhere rather than two). Without a separate breaker per client
 * here, a struggling {@code ledger-service} would get hammered on every
 * single poll cycle (every 100ms) with no backoff at all, since nothing
 * was guarding the ACTUAL downstream calls. Two independent breakers also
 * means a down {@code fx-rate-service} can't affect calls to {@code
 * ledger-service}, or vice versa.
 *
 * <p>These two breakers ALSO need {@code ignoreExceptions(...)}, unlike
 * {@code ServiceTokenProvider}'s own — a 4xx here means the downstream
 * service IS up and responded, it just rejected this one request; that
 * distinction has no equivalent for a token-endpoint response, which is
 * always simply success or failure.
 */
@Configuration
public class BeansConfig {

  /** Same threshold/cooldown {@code ServiceTokenProvider}'s own internal breaker config uses — no reason for these two numbers to differ. */
  private static final CircuitBreakerConfig DOWNSTREAM_CALL_CIRCUIT_BREAKER_CONFIG = CircuitBreakerConfig.custom()
      .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
      .slidingWindowSize(5)
      .minimumNumberOfCalls(5)
      .failureRateThreshold(100) // together with the sliding window above: "5 consecutive failures," not a lower-than-100% rate over a wider window
      .waitDurationInOpenState(Duration.ofSeconds(30))
      .permittedNumberOfCallsInHalfOpenState(1)
      // A 4xx (including 401) means the downstream service IS up and
      // responded — it just rejected this one request. Only a genuine
      // 5xx/unreachable-host failure should ever count against this
      // breaker; HttpClientErrorException still propagates to the
      // caller normally, it's just not counted as a failure here.
      .ignoreExceptions(org.springframework.web.client.HttpClientErrorException.class)
      .build();

  @Bean
  public JwtVerifier jwtVerifier(
      @Value("${lynx.security.jwks-url}") String jwksUrl,
      @Value("${lynx.security.issuer}") String issuer,
      @Value("${lynx.security.audience}") String audience) {
    return JwtVerifier.fromJwksUrl(jwksUrl, issuer, audience);
  }

  @Bean
  public ServiceTokenProvider serviceTokenProvider(
      @Value("${lynx.service-token.token-endpoint}") String tokenEndpoint,
      @Value("${lynx.service-token.client-id}") String clientId,
      @Value("${lynx.service-token.client-secret}") String clientSecret) {
    return new ServiceTokenProvider(HttpClient.newHttpClient(), URI.create(tokenEndpoint), clientId, clientSecret);
  }

  @Bean
  public LedgerServiceClient ledgerServiceClient(
      @Value("${lynx.clients.ledger-service.base-url}") String baseUrl,
      ServiceTokenProvider serviceTokenProvider) {
    CircuitBreaker circuitBreaker = CircuitBreaker.of("ledger-service", DOWNSTREAM_CALL_CIRCUIT_BREAKER_CONFIG);
    return new LedgerServiceClient(
        RestClient.builder().baseUrl(baseUrl).build(), serviceTokenProvider, circuitBreaker);
  }

  @Bean
  public FxRateServiceClient fxRateServiceClient(
      @Value("${lynx.clients.fx-rate-service.base-url}") String baseUrl,
      ServiceTokenProvider serviceTokenProvider) {
    CircuitBreaker circuitBreaker = CircuitBreaker.of("fx-rate-service", DOWNSTREAM_CALL_CIRCUIT_BREAKER_CONFIG);
    return new FxRateServiceClient(
        RestClient.builder().baseUrl(baseUrl).build(), serviceTokenProvider, circuitBreaker);
  }
}
