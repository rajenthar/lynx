package com.lynx.account.client;

import com.lynx.money.Money;
import com.lynx.security.ServiceTokenException;
import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls {@code ledger-service}'s deposit endpoint — this
 * service's own identity, ADR-007 Option C, same shape {@code
 * saga-orchestrator}'s downstream clients use: a resilience4j {@link
 * CircuitBreaker} guarding the call, {@link ServiceTokenProvider} for the
 * service-identity token, {@code onBehalfOfUserId} asserting the real end
 * user this deposit is for.
 *
 * <p>Deliberately its own small class, not built on a shared {@code
 * AbstractServiceClient} base — there's exactly one downstream call in this
 * whole service, unlike {@code saga-orchestrator}'s two; a shared
 * abstraction for a single caller would be premature.
 */
public class LedgerServiceClient {

  private static final Logger log = LoggerFactory.getLogger(LedgerServiceClient.class);

  private final RestClient restClient;
  private final ServiceTokenProvider tokenProvider;
  private final CircuitBreaker circuitBreaker;

  public LedgerServiceClient(RestClient restClient, ServiceTokenProvider tokenProvider,
                              CircuitBreaker circuitBreaker) {
    this.restClient = restClient;
    this.tokenProvider = tokenProvider;
    this.circuitBreaker = circuitBreaker;
  }

  /**
   * @param depositId this deposit's own write-identity — a
   *     fresh UUID per call; retried automatically by nothing today (no
   *     saga wraps this), so a caller that wants at-most-once on ITS OWN
   *     retry must reuse the same {@code depositId}.
   */
  public void deposit(UUID depositId, String userId, UUID accountId, Money amount) {
    String token;
    try {
      token = tokenProvider.currentToken();
    } catch (CallNotPermittedException | ServiceTokenException e) {
      log.error("Could not obtain a service token for ledger-service call", e);
      throw new LedgerUnavailableException("ledger-service is temporarily unavailable — please retry", e);
    }
    Map<String, Object> body = Map.of(
        "accountId", accountId,
        "amount", amount.amount(),
        "currencyCode", amount.currencyCode(),
        "onBehalfOfUserId", userId);
    try {
      circuitBreaker.executeRunnable(() -> restClient.post()
          .uri("/v1/ledger/deposits/{depositId}", depositId)
          .header("Authorization", "Bearer " + token)
          .body(body)
          .retrieve()
          .toBodilessEntity());
    } catch (CallNotPermittedException e) {
      throw new LedgerUnavailableException("ledger-service is temporarily unavailable — please retry", e);
    } catch (HttpClientErrorException e) {
      // A genuine rejection (e.g. malformed request) — not transient, the
      // caller (AccountService) surfaces this as a real failure, not a retry.
      throw new LedgerRejectedException(e.getMessage());
    } catch (HttpServerErrorException | ResourceAccessException e) {
      log.warn("ledger-service deposit call failed for depositId={}", depositId, e);
      throw new LedgerUnavailableException("ledger-service is temporarily unavailable — please retry", e);
    }
  }

  /**
   * The audit trail for one account — ownership must already be verified
   * by the caller ({@code AccountService.getAccountHistory}) BEFORE this is
   * called: ledger-service's endpoint has no notion of account ownership at
   * all (see its own controller javadoc), so this service is the one place
   * that check can happen. No {@code onBehalfOfUserId} needed — unlike
   * {@code deposit}, nothing here is scoped to a specific end user from
   * ledger-service's point of view, only to an accountId already confirmed
   * to belong to the caller.
   */
  public List<HistoryEntry> history(UUID accountId, int limit) {
    String token;
    try {
      token = tokenProvider.currentToken();
    } catch (CallNotPermittedException | ServiceTokenException e) {
      log.error("Could not obtain a service token for ledger-service call", e);
      throw new LedgerUnavailableException("ledger-service is temporarily unavailable — please retry", e);
    }
    try {
      return circuitBreaker.executeSupplier(() -> restClient.get()
          .uri("/v1/ledger/accounts/{accountId}/history?limit={limit}", accountId, limit)
          .header("Authorization", "Bearer " + token)
          .retrieve()
          .body(new ParameterizedTypeReference<List<HistoryEntry>>() { }));
    } catch (CallNotPermittedException e) {
      throw new LedgerUnavailableException("ledger-service is temporarily unavailable — please retry", e);
    } catch (HttpServerErrorException | ResourceAccessException e) {
      log.warn("ledger-service history call failed for accountId={}", accountId, e);
      throw new LedgerUnavailableException("ledger-service is temporarily unavailable — please retry", e);
    }
  }

  /** Mirrors ledger-service's own {@code LedgerHistoryEntryView} — deliberately a separate, loosely-coupled copy, same as every other cross-service DTO in this project. */
  public record HistoryEntry(
      UUID sagaId, String entryType, UUID accountId, BigDecimal amount, String currency, Instant createdAt) {
  }

  /** Transient — the caller may retry with the SAME {@code depositId}. */
  public static final class LedgerUnavailableException extends RuntimeException {
    public LedgerUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Not transient — ledger-service genuinely rejected this deposit. */
  public static final class LedgerRejectedException extends RuntimeException {
    public LedgerRejectedException(String message) {
      super(message);
    }
  }
}
