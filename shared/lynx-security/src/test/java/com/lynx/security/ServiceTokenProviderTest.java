package com.lynx.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code HttpClient} is mocked directly (Mockito) rather than standing up a
 * real embedded server — the OAuth2 Client Credentials wire shape (RFC 6749
 * §5.1) is fixed and well-known, so a real server adds no proof value here,
 * same reasoning {@code LedgerServiceTest} uses mocked repositories instead
 * of real Postgres for its unit-level scenarios.
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

  private HttpClient httpClient;
  private MutableClock clock;
  private ServiceTokenProvider provider;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    httpClient = mock(HttpClient.class);
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

  @SuppressWarnings("unchecked")
  private void stubTokenResponse(int statusCode, String body) throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    when(response.body()).thenReturn(body);
    when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(response);
  }

  @Test
  void firstCallFetchesAndCachesTheToken() throws Exception {
    stubTokenResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    assertEquals("tok-1", provider.currentToken());
    verify(httpClient, times(1)).send(any(HttpRequest.class), any());
  }

  @Test
  void secondCallWithinTtlReusesTheCache_noSecondHttpCall() throws Exception {
    stubTokenResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    provider.currentToken();
    clock.advance(Duration.ofMinutes(30)); // well within the 50-minute reuse window
    String second = provider.currentToken();

    assertEquals("tok-1", second);
    verify(httpClient, times(1)).send(any(HttpRequest.class), any()); // only ONE real fetch
  }

  @Test
  void afterTtlBufferElapsedItRefetches() throws Exception {
    stubTokenResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
    provider.currentToken();

    clock.advance(Duration.ofMinutes(51)); // past the 50-minute reuse window (10-min buffer of the 60-min token)
    stubTokenResponse(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    assertEquals("tok-2", provider.currentToken());
    verify(httpClient, times(2)).send(any(HttpRequest.class), any());
  }

  @Test
  void invalidateForcesAFreshFetchRegardlessOfRemainingTtl() throws Exception {
    stubTokenResponse(200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
    provider.currentToken();

    provider.invalidate();
    stubTokenResponse(200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    assertEquals("tok-2", provider.currentToken()); // fresh, even though tok-1 hadn't expired
    verify(httpClient, times(2)).send(any(HttpRequest.class), any());
  }

  @Test
  void nonSuccessResponseThrowsServiceTokenException() throws Exception {
    stubTokenResponse(500, "server error");

    assertThrows(ServiceTokenException.class, () -> provider.currentToken());
  }

  @Test
  void repeatedFailuresOpenTheCircuitAndStopCallingTheEndpoint() throws Exception {
    stubTokenResponse(500, "server error");

    for (int i = 0; i < 5; i++) {
      assertThrows(ServiceTokenException.class, () -> provider.currentToken());
    }
    // Circuit now open (threshold=5) — the 6th attempt must fail fast WITHOUT
    // calling the token endpoint again.
    assertThrows(CallNotPermittedException.class, () -> provider.currentToken());
    verify(httpClient, times(5)).send(any(HttpRequest.class), any());
  }
}
