package com.lynx.transaction.client;

/**
 * A downstream call (to {@code saga-orchestrator} or {@code
 * account-service}) failed for a TRANSIENT reason — unreachable, a 5xx, or
 * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException}
 * from either a downstream client's own breaker or {@code
 * ServiceTokenProvider}'s internal one guarding the token fetch. Same
 * shape as {@code saga-orchestrator}'s own client exception of the same
 * name — copied, not shared, same reasoning as everywhere else in this
 * project a two-class pair is small enough not to be worth a cross-module
 * dependency for.
 */
public final class DownstreamUnavailableException extends RuntimeException {

  public DownstreamUnavailableException(String message) {
    super(message);
  }

  public DownstreamUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
