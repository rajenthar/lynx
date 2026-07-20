package com.lynx.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class InMemoryIdempotencyCacheTest {

  /** Controllable clock: tests move time instead of sleeping. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-07-12T10:00:00Z");

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
  void putAndGetRoundTrip() {
    InMemoryIdempotencyCache cache = new InMemoryIdempotencyCache();
    cache.put("user-1|key-1", "response-body", Duration.ofHours(24));
    assertEquals("response-body", cache.get("user-1|key-1", String.class).orElseThrow());
  }

  @Test
  void missForUnknownKey() {
    InMemoryIdempotencyCache cache = new InMemoryIdempotencyCache();
    assertTrue(cache.get("nope", String.class).isEmpty());
  }

  @Test
  void entryExpiresAfterTtl() {
    MutableClock clock = new MutableClock();
    InMemoryIdempotencyCache cache = new InMemoryIdempotencyCache(clock);

    cache.put("user-1|key-1", "response", Duration.ofHours(24));
    assertTrue(cache.get("user-1|key-1", String.class).isPresent());

    clock.advance(Duration.ofHours(25));   // past the 24h TTL
    assertTrue(cache.get("user-1|key-1", String.class).isEmpty());
  }

  @Test
  void putSweepsExpiredEntriesAtThreshold() {
    // Guards against unbounded growth: expired entries that are NEVER read
    // again must still get purged eventually (lazy expiry alone can't do it).
    MutableClock clock = new MutableClock();
    InMemoryIdempotencyCache cache = new InMemoryIdempotencyCache(clock, 3);

    cache.put("k1", "v", Duration.ofMinutes(1));
    cache.put("k2", "v", Duration.ofMinutes(1));
    cache.put("k3", "v", Duration.ofMinutes(1));
    assertEquals(3, cache.size());

    clock.advance(Duration.ofMinutes(2));          // all three expire, none re-read
    cache.put("k4", "v", Duration.ofMinutes(1));   // size >= threshold → sweep first

    assertEquals(1, cache.size());                 // k1..k3 purged, only k4 remains
  }
}
