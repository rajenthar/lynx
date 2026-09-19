package com.lynx.transaction.client;

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
 * Same shape/purpose as {@code saga-orchestrator}'s own
 * {@code LedgerServiceClientCircuitBreakerTest} — proves a real HTTP
 * response is classified correctly (transient 5xx vs a real 4xx rejection)
 * BEFORE it ever counts toward tripping this client's breaker.
 */
class SagaOrchestratorClientCircuitBreakerTest {

  private MockRestServiceServer mockServer;
  private SagaOrchestratorClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://saga-orchestrator");
    mockServer = MockRestServiceServer.bindTo(builder).build();
    ServiceTokenProvider tokenProvider = mock(ServiceTokenProvider.class);
    when(tokenProvider.currentToken()).thenReturn("tok");
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(2)
        .minimumNumberOfCalls(2)
        .failureRateThreshold(100)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .ignoreExceptions(HttpClientErrorException.class)
        .build();
    CircuitBreaker circuitBreaker = CircuitBreaker.of("test", config);
    client = new SagaOrchestratorClient(builder.build(), tokenProvider, circuitBreaker);
  }

  private void expectCreate(org.springframework.test.web.client.response.DefaultResponseCreator response) {
    mockServer.expect(method(HttpMethod.POST)).andRespond(response);
  }

  @Test
  void twoConsecutive5xxTripTheBreakerThenTheThirdCallFailsFastWithoutAnHttpRequest() {
    expectCreate(withServerError());
    expectCreate(withServerError());

    assertThatThrownBy(() -> createSaga(client)).isInstanceOf(DownstreamUnavailableException.class);
    assertThatThrownBy(() -> createSaga(client)).isInstanceOf(DownstreamUnavailableException.class);
    assertThatThrownBy(() -> createSaga(client))
        .isInstanceOf(DownstreamUnavailableException.class)
        .cause()
        .isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

    mockServer.verify();
  }

  @Test
  void aReal4xxRejectionNeverCountsTowardTrippingTheBreaker() {
    expectCreate(withStatus(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"VALIDATION_ERROR\",\"message\":\"onBehalfOfUserId is required\"}"));
    expectCreate(withStatus(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"code\":\"VALIDATION_ERROR\",\"message\":\"onBehalfOfUserId is required\"}"));
    expectCreate(withSuccess("{\"sagaId\":\"" + UUID.randomUUID()
        + "\",\"status\":\"HOLDING\",\"failureReason\":null,\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}",
        MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> createSaga(client))
        .isInstanceOf(DownstreamRejectedException.class)
        .hasMessageContaining("onBehalfOfUserId is required");
    assertThatThrownBy(() -> createSaga(client)).isInstanceOf(DownstreamRejectedException.class);

    createSaga(client); // circuit still CLOSED — this reaches the mock instead of failing fast

    mockServer.verify();
  }

  private static SagaOrchestratorClient.SagaSummary createSaga(SagaOrchestratorClient client) {
    return client.createSaga(UUID.randomUUID(), "user-1", UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("100.00"), "SGD", "SGD");
  }
}
