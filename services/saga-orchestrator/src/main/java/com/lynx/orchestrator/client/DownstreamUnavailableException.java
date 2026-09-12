package com.lynx.orchestrator.client;

/**
 * A downstream call (to {@code ledger-service} or {@code fx-rate-service})
 * failed for a TRANSIENT reason — unreachable, a 5xx, or
 * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
 * from either a downstream client's own breaker or {@code
 * ServiceTokenProvider}'s internal one guarding the token fetch. Per
 * other-docs/10's confirmed decision: this is NOT a reason to fail the saga — the caller
 * (see {@code SagaOrchestratorService.processOne}) deliberately leaves the
 * saga at its current status on this exception, so the next poll or the
 * recovery worker simply retries the SAME step. Safe by construction:
 * every downstream call this service makes is itself idempotent.
 *
 * <p>Deliberately a plain {@code RuntimeException}, not part of
 * {@code lynx-common}'s sealed {@code LynxException} hierarchy — same
 * reasoning as {@code ServiceTokenException}/{@code DuplicateRequestException}:
 * an infrastructure failure of a client reaching a dependency, not a
 * client-facing business error.
 */
public final class DownstreamUnavailableException extends RuntimeException {

  public DownstreamUnavailableException(String message) {
    super(message);
  }

  public DownstreamUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
