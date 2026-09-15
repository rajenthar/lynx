package com.lynx.account.client;

import com.lynx.money.Money;
import com.lynx.security.ServiceTokenException;
import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls {@code ledger-service}'s deposit endpoint (other-docs/12) — this
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
   * @param depositId this deposit's own write-identity (other-docs/12) — a
   *     fresh UUID per call; retried automatically by nothing today (no
   *     saga wraps this), so a caller that wants at-most-once on ITS OWN
   *     retry must reuse the same {@code depositId}.
   */
  public void deposit(UUID depositId, String userId, UUID accountId, Money amount) {
    String token;
    try {
      token = tokenProvider.currentToken();
    } catch (CallNotPermittedException | ServiceTokenException e) {
      throw new LedgerUnavailableException("Could not obtain a service token: " + e.getMessage(), e);
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
      throw new LedgerUnavailableException("Circuit open for ledger-service: " + e.getMessage(), e);
    } catch (HttpClientErrorException e) {
      // A genuine rejection (e.g. malformed request) — not transient, the
      // caller (AccountService) surfaces this as a real failure, not a retry.
      throw new LedgerRejectedException(e.getMessage());
    } catch (HttpServerErrorException | ResourceAccessException e) {
      log.warn("ledger-service deposit call failed for depositId={}", depositId, e);
      throw new LedgerUnavailableException("Downstream call failed: " + e.getMessage(), e);
    }
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
