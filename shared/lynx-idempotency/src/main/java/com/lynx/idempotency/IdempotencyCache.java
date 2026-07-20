package com.lynx.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache of first-attempt responses, keyed by {@code user_id|idempotency_key}.
 *
 * <p><b>Speed only — never correctness.</b> The system must remain fully
 * correct with this cache wiped: the database UNIQUE constraint is the safety
 * net that is never bypassed (ADR-004). The cache exists so most retries are
 * answered in ~1ms without touching the database.
 *
 * <p>Implementations: {@link InMemoryIdempotencyCache} for tests and
 * single-instance use; services deploy a Redis-backed implementation so all
 * API instances share one cache.
 */
public interface IdempotencyCache {

  /** The cached response for this key, if present and not expired. */
  <T> Optional<T> get(String cacheKey, Class<T> type);

  /** Cache a response; entries expire after {@code ttl} (typically 24h). */
  void put(String cacheKey, Object response, Duration ttl);
}
