package com.lynx.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IdempotencyCache} for tests and single-instance deployments.
 *
 * <p>Expiry is handled two ways, still without any background thread:
 * <ul>
 *   <li><b>Lazily on read:</b> an expired entry is removed when next requested.
 *   <li><b>Sweep on write:</b> once the map reaches {@code sweepThreshold}
 *       entries, the next {@code put} first purges ALL expired entries — so
 *       keys that are never read again cannot accumulate unboundedly.
 * </ul>
 *
 * <p>The {@link Clock} is injectable so tests control time instead of sleeping.
 *
 * <p>NOT suitable for multi-instance services — each instance would have its
 * own private cache, so a retry landing on a different instance would miss.
 * Correctness still holds (the DB constraint catches it; ADR-004), but the
 * fast path is lost. Multi-instance services use the Redis implementation.
 */
public final class InMemoryIdempotencyCache implements IdempotencyCache {

  /** Map size at which a put() first sweeps out expired entries. */
  static final int DEFAULT_SWEEP_THRESHOLD = 10_000;

  private record Entry(Object value, Instant expiresAt) {
  }

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int sweepThreshold;

  public InMemoryIdempotencyCache() {
    this(Clock.systemUTC());
  }

  public InMemoryIdempotencyCache(Clock clock) {
    this(clock, DEFAULT_SWEEP_THRESHOLD);
  }

  public InMemoryIdempotencyCache(Clock clock, int sweepThreshold) {
    this.clock = clock;
    this.sweepThreshold = sweepThreshold;
  }

  @Override
  public <T> Optional<T> get(String cacheKey, Class<T> type) {
    Entry entry = entries.get(cacheKey);
    if (entry == null) {
      return Optional.empty();
    }
    if (clock.instant().isAfter(entry.expiresAt())) {
      entries.remove(cacheKey);
      return Optional.empty();
    }
    return Optional.of(type.cast(entry.value()));
  }

  @Override
  public void put(String cacheKey, Object response, Duration ttl) {
    if (entries.size() >= sweepThreshold) {
      sweepExpired();
    }
    entries.put(cacheKey, new Entry(response, clock.instant().plus(ttl)));
  }

  /** Current entry count (may include expired-but-unswept entries). */
  public int size() {
    return entries.size();
  }

  private void sweepExpired() {
    Instant now = clock.instant();
    entries.values().removeIf(entry -> now.isAfter(entry.expiresAt()));
  }
}
