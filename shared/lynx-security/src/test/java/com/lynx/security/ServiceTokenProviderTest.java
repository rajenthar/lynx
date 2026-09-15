package com.lynx.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code HttpClient} is faked with a hand-rolled subclass, not a Mockito
 * mock — {@code HttpClient} is a concrete (abstract) class, and this JDK's
 * Mockito inline mock maker can't instrument concrete classes (Byte
 * Buddy/JDK 25, same issue documented on {@code auth-service}'s
 * {@code JwtIssuerTest} and {@code ledger-service}'s {@code TransactionTemplate}
 * mocking). Standing up a real embedded server was rejected for a
 * different reason (still true): the OAuth2 Client Credentials wire shape
 * (RFC 6749 §5.1) is fixed and well-known, so a real server adds no proof
 * value here, same reasoning {@code LedgerServiceTest} uses mocked
 * repositories instead of real Postgres for its unit-level scenarios.
 */
class ServiceTokenProviderTest {

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-08-17T10:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  /** A queue of one canned (statusCode, body) response per {@code send} call, counting invocations. */
  private static final class FakeHttpClient extends HttpClient {
    final java.util.Queue<FakeHttpResponse> responses = new java.util.ArrayDeque<>();
    final AtomicInteger sendCount = new AtomicInteger();

    void queueResponse(int statusCode, String body) {
      responses.add(new FakeHttpResponse(statusCode, body));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      sendCount.incrementAndGet();
      FakeHttpResponse response = responses.poll();
      if (response == null) {
        throw new IllegalStateException("No response queued for this send() call");
      }
      return (HttpResponse<T>) response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException("not used by ServiceTokenProvider");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException("not used by ServiceTokenProvider");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException("not used by ServiceTokenProvider");
    }

    @Override
    public SSLParameters sslParameters() {
      throw new UnsupportedOperationException("not used by ServiceTokenProvider");
    }

    @Override
    public Optional<java.net.Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }

  /** Only {@code statusCode()}/{@code body()} matter to {@code ServiceTokenProvider.fetchToken} — everything else throws. */
  private static final class FakeHttpResponse implements HttpResponse<String> {
    private final int statusCode;
    private final String body;

    FakeHttpResponse(int statusCode, String body) {
      this.statusCode = statusCode;
      this.body = body;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public String body() {
      return body;
    }

    @Override
    public HttpRequest request() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<javax.net.ssl.SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient.Version version() {
      throw new UnsupportedOperationException();
    }
  }

  private FakeHttpClient httpClient;
  private MutableClock clock;
  private ServiceTokenProvider provider;

  @BeforeEach
  void setUp() {
    httpClient = new FakeHttpClient();
    clock = new MutableClock();
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(5)
        .minimumNumberOfCalls(5)
        .failureRateThreshold(100)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .build();
    provider = new ServiceTokenProvider(
        httpClient, URI.create("https://auth.lynx/token"), "ledger-service", "secret",
        clock, CircuitBreaker.of("test", config));
  }

  @Test
  void firstCallFetchesAndCachesTheToken() throws Exception {
    httpClient.queueResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    assertEquals("tok-1", provider.currentToken());
    assertEquals(1, httpClient.sendCount.get());
  }

  @Test
  void secondCallWithinTtlReusesTheCache_noSecondHttpCall() throws Exception {
    httpClient.queueResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    provider.currentToken();
    clock.advance(Duration.ofMinutes(30)); // well within the 50-minute reuse window
    String second = provider.currentToken();

    assertEquals("tok-1", second);
    assertEquals(1, httpClient.sendCount.get()); // only ONE real fetch
  }

  @Test
  void afterTtlBufferElapsedItRefetches() throws Exception {
    httpClient.queueResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
    provider.currentToken();

    clock.advance(Duration.ofMinutes(51)); // past the 50-minute reuse window (10-min buffer of the 60-min token)
    httpClient.queueResponse(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    assertEquals("tok-2", provider.currentToken());
    assertEquals(2, httpClient.sendCount.get());
  }

  @Test
  void invalidateForcesAFreshFetchRegardlessOfRemainingTtl() throws Exception {
    httpClient.queueResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
    provider.currentToken();

    provider.invalidate();
    httpClient.queueResponse(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    assertEquals("tok-2", provider.currentToken()); // fresh, even though tok-1 hadn't expired
    assertEquals(2, httpClient.sendCount.get());
  }

  @Test
  void nonSuccessResponseThrowsServiceTokenException() throws Exception {
    httpClient.queueResponse(500, "server error");

    assertThrows(ServiceTokenException.class, () -> provider.currentToken());
  }

  @Test
  void repeatedFailuresOpenTheCircuitAndStopCallingTheEndpoint() throws Exception {
    for (int i = 0; i < 5; i++) {
      httpClient.queueResponse(500, "server error");
    }

    for (int i = 0; i < 5; i++) {
      assertThrows(ServiceTokenException.class, () -> provider.currentToken());
    }
    // Circuit now open (threshold=5) — the 6th attempt must fail fast WITHOUT
    // calling the token endpoint again.
    assertThrows(CallNotPermittedException.class, () -> provider.currentToken());
    assertEquals(5, httpClient.sendCount.get());
  }
}
