package com.lynx.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

  /** Controllable clock: tests move time instead of sleeping (same pattern as lynx-idempotency's own tests). */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-08-17T10:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  @Test
  void closedCircuitLetsCallsThrough() {
    CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(30));

    assertEquals("ok", cb.call(() -> "ok"));
  }

  @Test
  void opensAfterConsecutiveFailuresReachThreshold() {
    CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(30));
    AtomicInteger attempts = new AtomicInteger();

    for (int i = 0; i < 3; i++) {
      assertThrows(RuntimeException.class, () -> cb.call(() -> {
        attempts.incrementAndGet();
        throw new RuntimeException("boom");
      }));
    }
    assertEquals(3, attempts.get());

    // 4th call: circuit is open — the operation itself must NOT run.
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> cb.call(() -> {
      attempts.incrementAndGet();
      return "unreachable";
    }));
    assertEquals(3, attempts.get(), "operation must not run while circuit is open");
  }

  @Test
  void afterCooldownATrialCallIsAllowedThrough_successCloses() {
    MutableClock clock = new MutableClock();
    CircuitBreaker cb = new CircuitBreaker(1, Duration.ofSeconds(30), clock);

    assertThrows(RuntimeException.class, () -> cb.call(() -> { throw new RuntimeException("boom"); }));
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> cb.call(() -> "should not run"));

    clock.advance(Duration.ofSeconds(31)); // cooldown elapses

    assertEquals("recovered", cb.call(() -> "recovered")); // half-open trial succeeds → closed
    assertEquals("still closed", cb.call(() -> "still closed")); // normal operation resumed
  }

  @Test
  void afterCooldownATrialCallFailureReopensTheCircuit() {
    MutableClock clock = new MutableClock();
    CircuitBreaker cb = new CircuitBreaker(1, Duration.ofSeconds(30), clock);

    assertThrows(RuntimeException.class, () -> cb.call(() -> { throw new RuntimeException("boom"); }));
    clock.advance(Duration.ofSeconds(31));

    // Half-open trial fails → circuit reopens immediately (not another 1-of-N count).
    assertThrows(RuntimeException.class, () -> cb.call(() -> { throw new RuntimeException("still down"); }));
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> cb.call(() -> "should not run"));
  }
}
