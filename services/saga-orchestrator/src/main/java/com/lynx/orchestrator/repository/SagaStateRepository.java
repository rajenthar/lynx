package com.lynx.orchestrator.repository;

import com.lynx.orchestrator.domain.SagaState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Native, not JPQL — {@code FOR UPDATE SKIP LOCKED} has no JPQL/Spring Data
 * equivalent (Spring Data's {@code @Lock(PESSIMISTIC_WRITE)} only ever
 * generates plain {@code FOR UPDATE}, which blocks rather than skipping —
 * see DECISIONS.md's corrected orchestrator-loop note and
 * other-docs/10's plan). Both queries are the exact pattern that doc
 * specifies: {@code ORDER BY} for a deterministic, largely-disjoint scan
 * order across concurrent instances, {@code LIMIT} for batching (never
 * drain the whole table in one claim), {@code FOR UPDATE SKIP LOCKED} so a
 * second instance skips straight past rows a first instance already
 * claimed instead of blocking on them.
 */
public interface SagaStateRepository extends JpaRepository<SagaState, Long> {

  /**
   * Scoped to {@code (sagaId, userId)}, not {@code sagaId} alone — other-docs/10
   * Decision 7: {@code sagaId} alone can't be mathematically guaranteed
   * collision-free (it's deterministically derived upstream from a real
   * client's own {@code Idempotency-Key}), and with {@code saga_id} no
   * longer the primary key, an unscoped lookup could return a DIFFERENT
   * user's saga entirely on a collision. Every real caller (creation,
   * lookup) always has {@code userId} in hand, so this is the only lookup
   * method that should ever be used — never add a plain
   * {@code findBySagaId}.
   */
  Optional<SagaState> findBySagaIdAndUserId(UUID sagaId, String userId);

  /**
   * Claims up to {@code limit} sagas currently at {@code status}, for the
   * calling transaction only — the row lock is held until that transaction
   * commits or rolls back. Must be called from within a {@code @Transactional}
   * method; the lock is meaningless (and the query would fail under
   * {@code SERIALIZABLE}) outside one.
   */
  @Query(value = "SELECT * FROM saga_state "
      + "WHERE status = :status "
      + "ORDER BY created_at "
      + "LIMIT :limit "
      + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
  List<SagaState> claimBatch(@Param("status") String status, @Param("limit") int limit);

  /**
   * The recovery worker's own claim — sagas stuck at a non-terminal status
   * whose {@code updated_at} is stale, meaning the LAST attempt to advance
   * them either crashed mid-step or hit a transient (downstream-unavailable)
   * failure and was never retried since. Ordered by {@code updated_at}, not
   * {@code created_at} — the longest-stuck saga first.
   */
  @Query(value = "SELECT * FROM saga_state "
      + "WHERE status IN ('HOLDING', 'LOCKED', 'EXECUTED') "
      + "AND updated_at < :staleBefore "
      + "ORDER BY updated_at "
      + "LIMIT :limit "
      + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
  List<SagaState> claimStale(@Param("staleBefore") Instant staleBefore, @Param("limit") int limit);

  /**
   * Test-only: {@code SagaState.setStatus} always stamps {@code updated_at}
   * with "now," so this is the only way to simulate a genuinely stale row
   * for {@link #claimStale} without a real 5-minute wait.
   */
  @Modifying
  @Query(value = "UPDATE saga_state SET updated_at = :updatedAt WHERE saga_id = :sagaId", nativeQuery = true)
  void backdateUpdatedAtForTest(@Param("sagaId") UUID sagaId, @Param("updatedAt") Instant updatedAt);
}
