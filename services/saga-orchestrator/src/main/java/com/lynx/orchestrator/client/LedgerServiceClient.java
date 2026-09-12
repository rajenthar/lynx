package com.lynx.orchestrator.client;

import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/**
 * The four saga-phase endpoints plus none of the read endpoints — this
 * service never needs {@code auditTrail}/{@code lockedRate} (it already
 * holds everything it wrote to {@code saga_state} itself). Every call
 * asserts {@code onBehalfOfUserId} (ADR-007 Option C) — this service always
 * calls as an {@code internal-service}-role token, on behalf of the saga's
 * real {@code userId}, never as an end-user itself.
 *
 * <p>Response bodies are deliberately ignored for all four calls
 * (see {@link AbstractServiceClient#call}'s javadoc) — {@code hold}/
 * {@code lock}/{@code settle}/{@code release} only need to succeed or
 * throw; the caller ({@code SagaOrchestratorService}) already knows
 * everything it needs from {@code saga_state} itself.
 */
public class LedgerServiceClient extends AbstractServiceClient {

  private final RestClient restClient;

  public LedgerServiceClient(RestClient restClient, ServiceTokenProvider tokenProvider,
                              CircuitBreaker circuitBreaker) {
    super(tokenProvider, circuitBreaker);
    this.restClient = restClient;
  }

  public void hold(UUID sagaId, String userId, UUID fromAccountId, BigDecimal amount, String currencyCode) {
    Map<String, Object> body = Map.of(
        "fromAccountId", fromAccountId,
        "amount", amount,
        "currencyCode", currencyCode,
        "onBehalfOfUserId", userId);
    call(token -> restClient.post()
        .uri("/v1/ledger/sagas/{sagaId}/hold", sagaId)
        .header("Authorization", "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(Void.class));
  }

  public void lock(UUID sagaId, String userId, BigDecimal lockedAmount, String currencyCode,
                    String fromCurrency, String toCurrency, BigDecimal rate, Instant rateExpiresAt) {
    Map<String, Object> body = Map.of(
        "lockedAmount", lockedAmount,
        "currencyCode", currencyCode,
        "fromCurrency", fromCurrency,
        "toCurrency", toCurrency,
        "rate", rate,
        "rateExpiresAt", rateExpiresAt.toString(),
        "onBehalfOfUserId", userId);
    call(token -> restClient.post()
        .uri("/v1/ledger/sagas/{sagaId}/lock", sagaId)
        .header("Authorization", "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(Void.class));
  }

  public void settle(UUID sagaId, String userId, UUID fromAccountId, UUID toAccountId,
                      BigDecimal debitedAmount, String debitedCurrency,
                      BigDecimal creditedAmount, String creditedCurrency) {
    Map<String, Object> body = Map.of(
        "fromAccountId", fromAccountId,
        "toAccountId", toAccountId,
        "debitedAmount", debitedAmount,
        "debitedCurrency", debitedCurrency,
        "creditedAmount", creditedAmount,
        "creditedCurrency", creditedCurrency,
        "onBehalfOfUserId", userId);
    call(token -> restClient.post()
        .uri("/v1/ledger/sagas/{sagaId}/settle", sagaId)
        .header("Authorization", "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(Void.class));
  }

  /**
   * {@code accountId} is the ORIGINAL sender's account — {@code
   * ledger-service} figures out internally which system pool
   * ({@code HOLD_POOL} vs {@code FX_LOCK}) to reverse from, based on this
   * saga's own ledger history (other-docs/08 Decision 20); the caller
   * never needs to track that itself.
   */
  public void release(UUID sagaId, String userId, UUID accountId, BigDecimal amount,
                       String currencyCode, String reason) {
    Map<String, Object> body = Map.of(
        "accountId", accountId,
        "amount", amount,
        "currencyCode", currencyCode,
        "reason", reason,
        "onBehalfOfUserId", userId);
    call(token -> restClient.post()
        .uri("/v1/ledger/sagas/{sagaId}/release", sagaId)
        .header("Authorization", "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(Void.class));
  }
}
