package com.lynx.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.lynx.common.error.ConflictException;
import com.lynx.common.error.ErrorCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One test per ADR-004 scenario. */
class IdempotencyGuardTest {

  private static final String USER = "user-123";
  private static final IdempotencyKey KEY =
      IdempotencyKey.of("550e8400-e29b-41d4-a716-446655440000");

  private InMemoryIdempotencyCache cache;
  private IdempotencyGuard guard;
  private AtomicInteger operationRuns;

  @BeforeEach
  void setUp() {
    cache = new InMemoryIdempotencyCache();
    guard = new IdempotencyGuard(cache);
    operationRuns = new AtomicInteger();
  }

  @Test
  void firstRequestRunsOperationAndCaches() {
    String result = guard.execute(USER, KEY, String.class,
        () -> {
          operationRuns.incrementAndGet();
          return "transfer-accepted";
        },
        Optional::empty);

    assertEquals("transfer-accepted", result);
    assertEquals(1, operationRuns.get());
    // response is now cached for retries
    assertEquals("transfer-accepted",
        cache.get(IdempotencyGuard.cacheKey(USER, KEY), String.class).orElseThrow());
  }

  @Test
  void retryIsServedFromCacheWithoutRerunningOperation() {
    guard.execute(USER, KEY, String.class,
        () -> {
          operationRuns.incrementAndGet();
          return "original";
        },
        Optional::empty);

    String retried = guard.execute(USER, KEY, String.class,
        () -> {
          operationRuns.incrementAndGet();
          return "MUST-NOT-HAPPEN";
        },
        Optional::empty);

    assertEquals("original", retried);       // first attempt's response replayed
    assertEquals(1, operationRuns.get());    // operation ran exactly once
  }

  @Test
  void duplicateWithCacheMissRecoversOriginalFromDatabase() {
    // Crash-before-cache scenario: first attempt COMMITTED to the DB but died
    // before cache.put — so the cache is empty, yet the DB knows the truth.
    String result = guard.execute(USER, KEY, String.class,
        () -> {
          throw new DuplicateRequestException("UNIQUE(saga_id, idempotency_key) violated");
        },
        () -> Optional.of("original-from-db"));   // recovery reads the DB, not the cache

    assertEquals("original-from-db", result);
    // recovered original is backfilled into the cache for the NEXT retry
    assertEquals("original-from-db",
        cache.get(IdempotencyGuard.cacheKey(USER, KEY), String.class).orElseThrow());
  }

  @Test
  void duplicateWithNothingRecoverableRespondsConflict409() {
    // Concurrent duplicate: original still in flight, its transaction not yet
    // committed — there IS no result to replay. Client should retry shortly.
    ConflictException e = assertThrows(ConflictException.class,
        () -> guard.execute(USER, KEY, String.class,
            () -> {
              throw new DuplicateRequestException("constraint violated");
            },
            Optional::empty));                    // DB has nothing readable yet

    assertEquals(ErrorCode.DUPLICATE_REQUEST, e.code());
  }

  @Test
  void differentUsersWithSameKeyDoNotShareCacheEntries() {
    guard.execute("user-A", KEY, String.class, () -> "response-A", Optional::empty);
    String resultB = guard.execute("user-B", KEY, String.class, () -> "response-B", Optional::empty);

    // user-B ran its own operation — it did NOT receive user-A's cached response
    assertEquals("response-B", resultB);
  }
}
