package com.lynx.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The one thing not already covered by {@code CircuitBreakerTest} (proves
 * the state machine in isolation) or {@code ServiceTokenProviderTest}
 * (proves the SAME pattern wired around the token fetch): that a
 * downstream-call breaker classifies real HTTP responses correctly before
 * counting anything as a failure — other-docs/10 Decision 8. Uses Spring's
 * {@link MockRestServiceServer} bound to a real {@link RestClient}, so the
 * actual {@code HttpServerErrorException}/{@code HttpClientErrorException}
 * mapping runs for real, not simulated.
 */
class LedgerServiceClientCircuitBreakerTest {

  private MockRestServiceServer mockServer;
  private LedgerServiceClient client;
  private ServiceTokenProvider tokenProvider;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://ledger-service");
    mockServer = MockRestServiceServer.bindTo(builder).build();
    tokenProvider = mock(ServiceTokenProvider.class);
    when(tokenProvider.currentToken()).thenReturn("tok");
    // A low threshold — 2, not the real service's 5 — so this test doesn't
    // need to send five requests to prove the same point. slidingWindowSize
    // == minimumNumberOfCalls == 2 with a 100% failure-rate requirement is
    // the resilience4j equivalent of "2 consecutive failures" (BeansConfig
    // uses the same shape at 5, for the real service).
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(2)
        .minimumNumberOfCalls(2)
        .failureRateThreshold(100)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .ignoreExceptions(HttpClientErrorException.class)
        .build();
    CircuitBreaker circuitBreaker = CircuitBreaker.of("test", config);
    client = new LedgerServiceClient(builder.build(), tokenProvider, circuitBreaker);
  }

  private void expectHold(org.springframework.test.web.client.response.DefaultResponseCreator response) {
    mockServer.expect(method(HttpMethod.POST)).andRespond(response);
  }

  @Test
  void twoConsecutive5xxTripTheBreakerThenTheThirdCallFailsFastWithoutAnHttpRequest() {
    expectHold(withServerError());
    expectHold(withServerError());

    assertThatThrownBy(() -> client.hold(UUID.randomUUID(), "user-1", UUID.randomUUID(), new BigDecimal("100.00"), "SGD"))
        .isInstanceOf(DownstreamUnavailableException.class);
    assertThatThrownBy(() -> client.hold(UUID.randomUUID(), "user-1", UUID.randomUUID(), new BigDecimal("100.00"), "SGD"))
        .isInstanceOf(DownstreamUnavailableException.class);

    // Circuit now OPEN (threshold=2) — a third call must fail fast WITHOUT
    // sending a request at all; MockRestServiceServer.verify() below
    // confirms only the two expectations above were ever actually called.
    assertThatThrownBy(() -> client.hold(UUID.randomUUID(), "user-1", UUID.randomUUID(), new BigDecimal("100.00"), "SGD"))
        .isInstanceOf(DownstreamUnavailableException.class)
        .hasMessageContaining("Circuit open");

    mockServer.verify();
  }

  @Test
  void aRealBusinessRejection4xxNeverCountsTowardTrippingTheBreaker() {
    // Threshold is 2 — if a 4xx incorrectly counted as a breaker failure,
    // two of these would trip the circuit and the third call below would
    // fail fast instead of actually reaching the (successful) mock.
    expectHold(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"not enough funds\"}"));
    expectHold(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"not enough funds\"}"));
    expectHold(withSuccess());

    assertThatThrownBy(() -> client.hold(UUID.randomUUID(), "user-1", UUID.randomUUID(), new BigDecimal("100.00"), "SGD"))
        .isInstanceOf(SagaStepFailedException.class)
        .hasMessageContaining("not enough funds");
    assertThatThrownBy(() -> client.hold(UUID.randomUUID(), "user-1", UUID.randomUUID(), new BigDecimal("100.00"), "SGD"))
        .isInstanceOf(SagaStepFailedException.class);

    // Circuit must still be CLOSED — this real request actually reaches
    // the mock server rather than failing fast.
    client.hold(UUID.randomUUID(), "user-1", UUID.randomUUID(), new BigDecimal("100.00"), "SGD");

    mockServer.verify();
  }
}
