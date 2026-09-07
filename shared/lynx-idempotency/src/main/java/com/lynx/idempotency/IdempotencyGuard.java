package com.lynx.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import com.lynx.common.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

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
   * Execute {@code operation} idempotently for
   * {@code (userId, idempotencyKey, operationType)}.
   *
   * @param userId          the caller's identity scoping this operation —
   *                        typically an authenticated end-user (JWT
   *                        {@code sub}), but a fixed system-caller value for
   *                        a purely service-to-service operation (e.g.
   *                        {@code fx-rate-service}'s {@code FX_EXECUTE}).
   *                        Part of the cache key, so two different callers
   *                        using the same key never collide
   * @param idempotencyKey  the validated key identifying this attempt — a
   *                        real client-supplied {@code Idempotency-Key} for
   *                        a service that has one, or an internally-derived
   *                        value (e.g. {@code ledger-service}'s own
   *                        {@code sagaId}) for a service that doesn't need
   *                        a separate client key at all
   * @param operationType   a stable discriminator for WHAT is being executed
   *                        (e.g. {@code "HOLD"}, {@code "LOCK"}), part of the
   *                        cache key alongside {@code userId}/{@code idempotencyKey}. A
   *                        service may legitimately expose several distinct
   *                        idempotent operations (e.g. ledger-service's
   *                        hold/lock/settle/release, one per saga phase); if
   *                        a caller ever reused the same {@code idempotencyKey} across
   *                        two different operations for the same
   *                        {@code userId}, omitting this discriminator would
   *                        let the SECOND operation's cache lookup collide
   *                        with the FIRST operation's cached response and
   *                        silently replay the wrong result instead of
   *                        running. Persistence-layer uniqueness (e.g.
   *                        ledger-service's {@code UNIQUE(saga_id,
   *                        idempotency_key, entry_type)}) already guards
   *                        against this at the database level per operation;
   *                        this parameter gives the cache — which sits in
   *                        front of the database and can short-circuit
   *                        before that constraint is ever checked — the same
   *                        protection.
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
  public <T> T execute(String userId, IdempotencyKey idempotencyKey, String operationType,
                       Class<T> responseType,
                       Supplier<T> operation, Supplier<Optional<T>> recoverOriginal) {
    String cacheKey = cacheKey(userId, idempotencyKey, operationType);

    // 1. fast path — most retries are served here without touching the DB
    Optional<T> cached = cache.get(cacheKey, responseType);
    if (cached.isPresent()) {
      log.debug("Idempotency cache HIT for [{}]", cacheKey);
      return cached.get();
    }
    log.debug("Idempotency cache MISS for [{}] — running the operation", cacheKey);

    T result;
    try {
      // 2. just try — the DB's UNIQUE constraint is the judge
      result = operation.get();
    } catch (DuplicateRequestException e) {
      // 3. duplicate confirmed by the DB; recover the original FROM THE DB
      //    (cache already missed in step 1 — crash-before-cache scenario)
      log.info("Duplicate detected by the DB for [{}] — recovering the original result", cacheKey);
      result = recoverOriginal.get()
          // 4. nothing readable yet: original still in flight → 409, retry shortly
          .orElseGet(() -> {
            log.warn("Duplicate for [{}] but original not yet readable — "
                + "responding 409, caller should retry shortly", cacheKey);
            throw ConflictException.duplicateRequest(idempotencyKey.value());
          });
    }

    // 5. cache success or recovered original for the next retry
    cache.put(cacheKey, result, ttl);
    return result;
  }

  /**
   * Cache key is user- and operation-scoped: user-scoped for the same reason
   * saga_id derivation is (two users, same key, never collide); operation-scoped
   * so a caller-reused key across two DIFFERENT operations (e.g. hold vs. lock
   * for the same saga) cannot cause one operation's cached response to be
   * replayed for the other.
   */
  static String cacheKey(String userId, IdempotencyKey idempotencyKey, String operationType) {
    return userId + "|" + idempotencyKey.value() + "|" + operationType;
  }
}
