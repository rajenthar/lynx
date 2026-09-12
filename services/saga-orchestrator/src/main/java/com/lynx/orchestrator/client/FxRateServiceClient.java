package com.lynx.orchestrator.client;

import com.lynx.orchestrator.client.dto.FxExecutionResult;
import com.lynx.orchestrator.client.dto.FxQuoteResult;
import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/**
 * The two {@code fx-rate-service} endpoints this service actually calls —
 * {@code quote} (cheap, unauthenticated today per fx-rate-service-flows.html's
 * known gaps, but called with a bearer token anyway so nothing here needs
 * to change once auth is wired there) and {@code execute} (the real trade
 * commitment).
 */
public class FxRateServiceClient extends AbstractServiceClient {

  private final RestClient restClient;

  public FxRateServiceClient(RestClient restClient, ServiceTokenProvider tokenProvider,
                              CircuitBreaker circuitBreaker) {
    super(tokenProvider, circuitBreaker);
    this.restClient = restClient;
  }

  public FxQuoteResult quote(BigDecimal amount, String fromCurrency, String toCurrency) {
    return call(token -> restClient.get()
        .uri(uriBuilder -> uriBuilder.path("/v1/fx/quotes")
            .queryParam("amount", amount)
            .queryParam("fromCurrency", fromCurrency)
            .queryParam("toCurrency", toCurrency)
            .build())
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(FxQuoteResult.class));
  }

  /**
   * @param executionId the deterministic id {@code SagaState} persisted the
   *     first time this saga reached {@code LOCKED} — MUST be reused
   *     verbatim on every redo (see {@code SagaState}'s javadoc); a fresh
   *     id per call would let a redo execute the same trade twice.
   */
  public FxExecutionResult execute(UUID executionId, UUID sagaId, BigDecimal amount,
                                    String fromCurrency, String toCurrency, BigDecimal requestedRate) {
    Map<String, Object> body = Map.of(
        "executionId", executionId,
        "sagaId", sagaId,
        "amount", amount,
        "fromCurrency", fromCurrency,
        "toCurrency", toCurrency,
        "requestedRate", requestedRate);
    return call(token -> restClient.post()
        .uri("/v1/fx/executions")
        .header("Authorization", "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(FxExecutionResult.class));
  }
}
