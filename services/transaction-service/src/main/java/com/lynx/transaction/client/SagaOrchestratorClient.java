package com.lynx.transaction.client;

import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/**
 * {@code saga-orchestrator}'s two internal endpoints —
 * always as this service's own service-identity token (ADR-007), always
 * with {@code onBehalfOfUserId} set to the real end user this
 * transaction-service request came in for.
 */
public class SagaOrchestratorClient extends AbstractServiceClient {

  private final RestClient restClient;

  public SagaOrchestratorClient(RestClient restClient, ServiceTokenProvider tokenProvider,
                                 CircuitBreaker circuitBreaker) {
    super(tokenProvider, circuitBreaker);
    this.restClient = restClient;
  }

  /** {@code CreateSagaRequest}'s exact shape (saga-orchestrator's dto) — a local copy, not a shared DTO, same convention as every other cross-service call in this project. */
  private record CreateSagaRequestBody(
      UUID sagaId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
      String fromCurrency, String toCurrency, UUID retriedFromSagaId, String onBehalfOfUserId) {
  }

  public SagaSummary createSaga(UUID sagaId, String userId, UUID fromAccountId, UUID toAccountId,
                                 BigDecimal amount, String fromCurrency, String toCurrency) {
    CreateSagaRequestBody body = new CreateSagaRequestBody(
        sagaId, fromAccountId, toAccountId, amount, fromCurrency, toCurrency, null, userId);
    return call(token -> restClient.post()
        .uri("/internal/sagas")
        .header("Authorization", "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(SagaSummary.class));
  }

  public SagaSummary getSaga(UUID sagaId, String userId) {
    return call(token -> restClient.get()
        .uri(uriBuilder -> uriBuilder.path("/internal/sagas/{sagaId}")
            .queryParam("onBehalfOfUserId", userId)
            .build(sagaId))
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(SagaSummary.class));
  }

  /** Matches {@code saga-orchestrator}'s own {@code SagaResponse} shape. */
  public record SagaSummary(UUID sagaId, String status, String failureReason,
                             Instant createdAt, Instant updatedAt) {
  }
}
