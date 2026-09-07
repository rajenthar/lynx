package com.lynx.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal, hand-rolled circuit breaker — same "small, focused,
 * dependency-free primitive" philosophy as {@code IdempotencyGuard}
 * (lynx-idempotency), rather than pulling in a library like resilience4j
 * for something this simply stated.
 *
 * <p>Standard three-state behavior:
 * <ul>
 *   <li><b>CLOSED</b> — normal; calls pass through. A run of
 *       {@code failureThreshold} consecutive failures trips it OPEN.
 *   <li><b>OPEN</b> — calls fail fast (this class's own
 *       {@link CircuitOpenException}, never touching the guarded
 *       operation) until {@code openDuration} has elapsed.
 *   <li><b>HALF_OPEN</b> — after the cooldown, the NEXT call is let
 *       through as a trial: success closes the circuit again, failure
 *       reopens it (resetting the cooldown).
 * </ul>
 *
 * <p>Built for {@link ServiceTokenProvider} to guard calls to
 * {@code auth-service}'s token endpoint specifically — see ADR-007's
 * "Implementation Details" section. Deliberately generic (takes any
 * {@code Supplier<T>}), so it isn't tied to that one use case.
 */
public final class CircuitBreaker {

  private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

  private enum State { CLOSED, OPEN, HALF_OPEN }

  /** Thrown instead of ever invoking the guarded operation, while OPEN. */
  public static final class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String message) {
      super(message);
    }
  }

  private final int failureThreshold;
  private final Duration openDuration;
  private final Clock clock;

  private State state = State.CLOSED;
  private int consecutiveFailures = 0;
  private Instant openedAt;

  public CircuitBreaker(int failureThreshold, Duration openDuration) {
    this(failureThreshold, openDuration, Clock.systemUTC());
  }

  CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
    this.failureThreshold = failureThreshold;
    this.openDuration = openDuration;
    this.clock = clock;
  }

  /**
   * Runs {@code operation} if the circuit allows it; records the outcome.
   *
   * @throws CircuitOpenException if OPEN and the cooldown hasn't elapsed —
   *     {@code operation} is never invoked in this case
   */
  public synchronized <T> T call(Supplier<T> operation) {
    if (state == State.OPEN) {
      if (Duration.between(openedAt, clock.instant()).compareTo(openDuration) < 0) {
        throw new CircuitOpenException(
            "Circuit open, retry after " + openDuration.minus(Duration.between(openedAt, clock.instant())));
      }
      // Cooldown elapsed — allow exactly one trial call through.
      log.info("Circuit cooldown elapsed — allowing one HALF_OPEN trial call through");
      state = State.HALF_OPEN;
    }

    try {
      T result = operation.get();
      onSuccess();
      return result;
    } catch (RuntimeException e) {
      onFailure();
      throw e;
    }
  }

  private void onSuccess() {
    if (state != State.CLOSED) {
      log.info("Circuit recovered — closing (was {})", state);
    }
    consecutiveFailures = 0;
    state = State.CLOSED;
  }

  private void onFailure() {
    consecutiveFailures++;
    if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
      log.warn("Circuit opening after {} consecutive failure(s) (threshold {}) — "
          + "calls will fail fast for {}", consecutiveFailures, failureThreshold, openDuration);
      state = State.OPEN;
      openedAt = clock.instant();
    }
  }

  /** For tests/observability — not part of the operational contract. */
  boolean isOpen() {
    return state == State.OPEN;
  }
}
