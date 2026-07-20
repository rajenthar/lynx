package com.lynx.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import com.lynx.common.error.ConflictException;

/**
 * Encodes the ADR-004 idempotent-request flow, written once for all services:
 *
 * <pre>
 * 1. Cache check (fast path — PERFORMANCE ONLY; most retries end here)
 * 2. Just try the operation — the DATABASE is the judge of duplicates
 *    (the UNIQUE constraint rejects a retry; persistence code signals it
 *    by throwing {@link DuplicateRequestException})
 * 3. On duplicate: recover the ORIGINAL result from the database — NOT the
 *    cache. The first attempt may have crashed after committing but before
 *    caching, so the cache cannot be trusted here; the DB row is the truth.
 * 4. Recovery found nothing → the original is still in flight (its transaction
 *    hasn't committed): respond 409 DUPLICATE_REQUEST — "retry the same key
 *    shortly" — the one case with no result to replay yet.
 * 5. Whatever was produced or recovered is cached for the next retry.
 * </pre>
 *
 * <p>Core principle: cache for speed, database for correctness. Wipe the cache
 * entirely and every outcome is still correct — only latency changes.
 */
public final class IdempotencyGuard {

  /** ADR-004: covers the client retry window; nightly cleanup matches it. */
  public static final Duration DEFAULT_TTL = Duration.ofHours(24);

  private final IdempotencyCache cache;
  private final Duration ttl;

  public IdempotencyGuard(IdempotencyCache cache) {
    this(cache, DEFAULT_TTL);
  }

  public IdempotencyGuard(IdempotencyCache cache, Duration ttl) {
    this.cache = cache;
    this.ttl = ttl;
  }

  /**
   * Execute {@code operation} idempotently for {@code (userId, key)}.
   *
   * @param userId          authenticated caller (JWT {@code sub}); part of the
   *                        cache key AND of saga_id derivation, so two users
   *                        sending the same key never collide
   * @param key             validated client Idempotency-Key
   * @param responseType    response class (for type-safe cache reads)
   * @param operation       the attempt; its persistence layer throws
   *                        {@link DuplicateRequestException} when the UNIQUE
   *                        constraint rejects the write
   * @param recoverOriginal duplicate path: load the FIRST attempt's result from
   *                        the database (by the deterministically re-derived
   *                        saga_id); empty if the original hasn't committed yet
   * @throws ConflictException 409 DUPLICATE_REQUEST when a duplicate is
   *                           detected but the original result is not yet
   *                           readable (original still in flight)
   */
  public <T> T execute(String userId, IdempotencyKey key, Class<T> responseType,
                       Supplier<T> operation, Supplier<Optional<T>> recoverOriginal) {
    String cacheKey = cacheKey(userId, key);

    // 1. fast path — most retries are served here without touching the DB
    Optional<T> cached = cache.get(cacheKey, responseType);
    if (cached.isPresent()) {
      return cached.get();
    }

    T result;
    try {
      // 2. just try — the DB's UNIQUE constraint is the judge
      result = operation.get();
    } catch (DuplicateRequestException e) {
      // 3. duplicate confirmed by the DB; recover the original FROM THE DB
      //    (cache already missed in step 1 — crash-before-cache scenario)
      result = recoverOriginal.get()
          // 4. nothing readable yet: original still in flight → 409, retry shortly
          .orElseThrow(() -> ConflictException.duplicateRequest(key.value()));
    }

    // 5. cache success or recovered original for the next retry
    cache.put(cacheKey, result, ttl);
    return result;
  }

  /** Cache key is user-scoped for the same reason saga_id derivation is. */
  static String cacheKey(String userId, IdempotencyKey key) {
    return userId + "|" + key.value();
  }
}
