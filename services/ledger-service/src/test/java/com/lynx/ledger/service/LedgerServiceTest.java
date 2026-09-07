package com.lynx.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynx.common.error.ConflictException;
import com.lynx.idempotency.IdempotencyGuard;
import com.lynx.idempotency.InMemoryIdempotencyCache;
import com.lynx.ledger.domain.EntryType;
import com.lynx.ledger.domain.FxRateLock;
import com.lynx.ledger.domain.LedgerEntry;
import com.lynx.ledger.domain.OutboxEntry;
import com.lynx.ledger.domain.SagaPhase;
import com.lynx.ledger.domain.SystemAccounts;
import com.lynx.ledger.dto.LedgerLegView;
import com.lynx.ledger.dto.LedgerPhaseResponse;
import com.lynx.ledger.repository.FxRateLockRepository;
import com.lynx.ledger.repository.LedgerEntryRepository;
import com.lynx.ledger.repository.OutboxEntryRepository;
import com.lynx.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mocked repositories, no Postgres: proves the DR=CR invariant, the correct
 * event/leg construction per phase, and the idempotency-guard wiring
 * (retry for the same saga+phase returns the same response without a
 * second write). No {@code Idempotency-Key} (other-docs/08 Decision 29)
 * — {@code (userId, sagaId, phase)} is the
 * whole identity now, derived deterministically inside
 * {@code LedgerService} itself, not supplied by the test.
 */
class LedgerServiceTest {

  private LedgerEntryRepository ledgerEntryRepository;
  private OutboxEntryRepository outboxEntryRepository;
  private FxRateLockRepository fxRateLockRepository;
  private LedgerService ledgerService;
  private final List<LedgerEntry> savedLegs = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ledgerEntryRepository = mock(LedgerEntryRepository.class);
    outboxEntryRepository = mock(OutboxEntryRepository.class);
    fxRateLockRepository = mock(FxRateLockRepository.class);
    when(fxRateLockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    when(ledgerEntryRepository.saveAll(any())).thenAnswer(inv -> {
      List<LedgerEntry> legs = inv.getArgument(0);
      savedLegs.addAll(legs);
      return legs;
    });

    AtomicLong outboxSeq = new AtomicLong(1);
    when(outboxEntryRepository.saveAndFlush(any())).thenAnswer(inv -> {
      OutboxEntry entry = inv.getArgument(0);
      setId(entry, outboxSeq.getAndIncrement());
      return entry;
    });
    when(outboxEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    when(transactionTemplate.execute(any())).thenAnswer(inv -> {
      TransactionCallback<?> callback = inv.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });

    IdempotencyGuard idempotencyGuard = new IdempotencyGuard(new InMemoryIdempotencyCache());
    ledgerService = new LedgerService(
        ledgerEntryRepository, outboxEntryRepository, fxRateLockRepository,
        idempotencyGuard, transactionTemplate);
  }

  private static void setId(OutboxEntry entry, long id) {
    try {
      var field = OutboxEntry.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(entry, id);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void holdWritesBalancedDrCrLegs() {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));

    LedgerPhaseResponse response = ledgerService.hold(sagaId, "user-1", fromAccount, amount);

    assertEquals(2, response.legs().size());
    LedgerLegView dr = response.legs().get(0);
    LedgerLegView cr = response.legs().get(1);
    assertEquals(EntryType.HOLD_DR, dr.entryType());
    assertEquals(fromAccount, dr.accountId());
    assertEquals(EntryType.HOLD_CR, cr.entryType());
    assertEquals(SystemAccounts.HOLD_POOL, cr.accountId());
    assertEquals(0, dr.amount().compareTo(cr.amount()));
    assertEquals(2, savedLegs.size());
  }

  @Test
  void lockWritesHoldPoolToFxLockLegs() {
    UUID sagaId = UUID.randomUUID();
    Money locked = Money.of(new BigDecimal("50.00"), Money.currencyOf("SGD"));

    LedgerPhaseResponse response = ledgerService.lock(
        sagaId, "user-1", locked, "SGD", "USD", new BigDecimal("0.74"),
        Instant.now().plusSeconds(60));

    assertEquals(SystemAccounts.HOLD_POOL, response.legs().get(0).accountId());
    assertEquals(SystemAccounts.FX_LOCK, response.legs().get(1).accountId());
  }

  @Test
  void settleWritesFxLockToRecipientLegs() {
    UUID sagaId = UUID.randomUUID();
    UUID toAccount = UUID.randomUUID();
    Money debited = Money.of(new BigDecimal("50.00"), Money.currencyOf("SGD"));
    Money credited = Money.of(new BigDecimal("37.00"), Money.currencyOf("USD"));

    LedgerPhaseResponse response = ledgerService.settle(
        sagaId, "user-1", UUID.randomUUID(), toAccount, debited, credited);

    assertEquals(SystemAccounts.FX_LOCK, response.legs().get(0).accountId());
    assertEquals(toAccount, response.legs().get(1).accountId());
  }

  @Test
  void releaseWritesHoldPoolToAccountLegs() {
    UUID sagaId = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));

    LedgerPhaseResponse response = ledgerService.release(
        sagaId, "user-1", account, amount, "FX rate expired");

    assertEquals(SystemAccounts.HOLD_POOL, response.legs().get(0).accountId());
    assertEquals(account, response.legs().get(1).accountId());
  }

  @Test
  void releaseAfterLockReversesFxLockNotHoldPool() {
    // Regression test for other-docs/08 Decision 20: a saga that already
    // completed `lock` has its money in FX_LOCK, not HOLD_POOL — release
    // must reverse the account the money is ACTUALLY in.
    UUID sagaId = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));

    LedgerEntry priorLockCr = new LedgerEntry(sagaId, "user-1", EntryType.LOCK_CR,
        SystemAccounts.FX_LOCK, amount.amount(), amount.currencyCode());
    when(ledgerEntryRepository.findBySagaIdAndUserIdOrderByCreatedAtAsc(sagaId, "user-1"))
        .thenReturn(List.of(priorLockCr));

    LedgerPhaseResponse response = ledgerService.release(
        sagaId, "user-1", account, amount, "FX rate expired");

    assertEquals(SystemAccounts.FX_LOCK, response.legs().get(0).accountId());
    assertEquals(account, response.legs().get(1).accountId());
  }

  @Test
  void releaseAfterSettleIsRejected() {
    // Once settle has run, the money already left FX_LOCK for the
    // recipient — there is nothing left in either system account to
    // reverse, so release must refuse rather than silently do nothing
    // useful (or worse, debit an account with no relationship to the funds).
    UUID sagaId = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));

    LedgerEntry priorSettleDr = new LedgerEntry(sagaId, "user-1", EntryType.SETTLE_DR,
        SystemAccounts.FX_LOCK, amount.amount(), amount.currencyCode());
    when(ledgerEntryRepository.findBySagaIdAndUserIdOrderByCreatedAtAsc(sagaId, "user-1"))
        .thenReturn(List.of(priorSettleDr));

    assertThrows(ConflictException.class, () ->
        ledgerService.release(sagaId, "user-1", account, amount, "too late"));
  }

  @Test
  void lockedRateReadsBackTheRatePersistedInFxRateLocks() {
    // The rate is not just inside RateLocked's outbox payload — it must be
    // queryable straight from FxRateLock, since SETTLE (a separate, later
    // request) has no other way to find out which rate this saga locked in.
    UUID sagaId = UUID.randomUUID();
    Money lockedAmount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));

    Instant expiresAt = Instant.now().plusSeconds(60);
    ledgerService.lock(sagaId, "user-1", lockedAmount, "SGD", "USD",
        new BigDecimal("0.7412"), expiresAt);

    when(fxRateLockRepository.findBySagaIdAndUserId(sagaId, "user-1"))
        .thenReturn(Optional.of(
            new FxRateLock(sagaId, "user-1", new BigDecimal("0.7412"), "SGD", "USD", expiresAt)));

    var view = ledgerService.lockedRate(sagaId, "user-1");

    assertEquals(new BigDecimal("0.7412"), view.rate());
    assertEquals("SGD", view.fromCurrency());
    assertEquals("USD", view.toCurrency());
    assertEquals(expiresAt, view.expiresAt());
  }

  @Test
  void lockedRateThrowsNotFoundWhenSagaNeverReachedLock() {
    UUID sagaId = UUID.randomUUID();
    when(fxRateLockRepository.findBySagaIdAndUserId(sagaId, "user-1"))
        .thenReturn(Optional.empty());

    assertThrows(com.lynx.common.error.NotFoundException.class,
        () -> ledgerService.lockedRate(sagaId, "user-1"));
  }

  @Test
  void lockedRateThrowsNotFoundWhenTheLockBelongsToADifferentUser() {
    // Same collision/authz reasoning as auditTrail (other-docs/08 Decision
    // 30): a lock existing for this sagaId under a DIFFERENT userId must
    // not be readable — 404, indistinguishable from a saga that never
    // reached LOCK at all, so this endpoint can't be used to probe for
    // other users' saga ids.
    UUID sagaId = UUID.randomUUID();
    when(fxRateLockRepository.findBySagaIdAndUserId(sagaId, "user-2"))
        .thenReturn(Optional.empty());

    assertThrows(com.lynx.common.error.NotFoundException.class,
        () -> ledgerService.lockedRate(sagaId, "user-2"));
  }

  @Test
  void retryForSameSagaAndPhaseIsServedFromCacheWithoutASecondWrite() {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));

    LedgerPhaseResponse first = ledgerService.hold(sagaId, "user-1", fromAccount, amount);
    LedgerPhaseResponse second = ledgerService.hold(sagaId, "user-1", fromAccount, amount);

    assertEquals(first, second);
    assertTrue(savedLegs.size() == 2, "second call must not write new legs");
  }

  @Test
  void recoverFromDbScopesToThePhasesOwnEntryTypesAcrossASagaWithMultiplePhases() {
    // Since (userId, sagaId, entry_type) is the whole constraint now
    // (no more per-attempt idempotency_key), a query by
    // (sagaId, userId) alone spans EVERY phase that saga has ever gone
    // through. This is now the NORMAL case for any saga past its first
    // phase, not just a caller-mistake edge case — recovering ONE phase
    // must still return only that phase's own 2 legs.
    UUID sagaId = UUID.randomUUID();

    LedgerEntry holdDr = new LedgerEntry(sagaId, "user-1", EntryType.HOLD_DR,
        UUID.randomUUID(), new BigDecimal("100.00"), "SGD");
    LedgerEntry holdCr = new LedgerEntry(sagaId, "user-1", EntryType.HOLD_CR,
        SystemAccounts.HOLD_POOL, new BigDecimal("100.00"), "SGD");
    LedgerEntry lockDr = new LedgerEntry(sagaId, "user-1", EntryType.LOCK_DR,
        SystemAccounts.HOLD_POOL, new BigDecimal("100.00"), "SGD");
    LedgerEntry lockCr = new LedgerEntry(sagaId, "user-1", EntryType.LOCK_CR,
        SystemAccounts.FX_LOCK, new BigDecimal("100.00"), "SGD");
    when(ledgerEntryRepository.findBySagaIdAndUserIdOrderByCreatedAtAsc(sagaId, "user-1"))
        .thenReturn(List.of(holdDr, holdCr, lockDr, lockCr));

    var recovered = ledgerService.recoverFromDb(sagaId, "user-1", SagaPhase.LOCK);

    assertTrue(recovered.isPresent());
    assertEquals(2, recovered.get().legs().size(), "must contain only LOCK's own 2 legs");
    assertTrue(recovered.get().legs().stream()
        .allMatch(leg -> leg.entryType() == EntryType.LOCK_DR || leg.entryType() == EntryType.LOCK_CR));
  }

  @Test
  void assertBalancedFindsDrAndCrByTypeNotByListPosition() {
    // Reversed order (CR at index 0, DR at index 1) — nothing in the public
    // hold/lock/settle/release API actually produces this today (every call
    // site builds List.of(drLeg, crLeg)), but assertBalanced must not rely
    // on position anyway. A same-currency HOLD stays balanced regardless.
    UUID sagaId = UUID.randomUUID();
    LedgerEntry holdCr = new LedgerEntry(sagaId, "user-1", EntryType.HOLD_CR,
        SystemAccounts.HOLD_POOL, new BigDecimal("100.00"), "SGD");
    LedgerEntry holdDr = new LedgerEntry(sagaId, "user-1", EntryType.HOLD_DR,
        UUID.randomUUID(), new BigDecimal("100.00"), "SGD");

    LedgerService.assertBalanced(List.of(holdCr, holdDr));
  }

  @Test
  void assertBalancedDoesNotFalselyRejectAReversedSettleAsUnbalanced() {
    // The specific risk a position-based check would have gotten wrong:
    // SETTLE's DR/CR legs legitimately differ in amount/currency (FX
    // conversion). If assertBalanced inferred "which leg is DR" from index
    // 0 instead of actual entry_type, a reversed list would misread the CR
    // leg as DR, wrongly treat this as a same-currency clearing move, and
    // throw a false invariant violation on a perfectly valid settlement.
    UUID sagaId = UUID.randomUUID();
    LedgerEntry settleCr = new LedgerEntry(sagaId, "user-1", EntryType.SETTLE_CR,
        UUID.randomUUID(), new BigDecimal("74.00"), "USD");
    LedgerEntry settleDr = new LedgerEntry(sagaId, "user-1", EntryType.SETTLE_DR,
        SystemAccounts.FX_LOCK, new BigDecimal("100.00"), "SGD");

    LedgerService.assertBalanced(List.of(settleCr, settleDr));
  }

  @Test
  void assertBalancedRejectsTwoDrLegsWithNoCrLeg() {
    UUID sagaId = UUID.randomUUID();
    LedgerEntry dr1 = new LedgerEntry(sagaId, "user-1", EntryType.HOLD_DR,
        UUID.randomUUID(), new BigDecimal("100.00"), "SGD");
    LedgerEntry dr2 = new LedgerEntry(sagaId, "user-1", EntryType.HOLD_DR,
        UUID.randomUUID(), new BigDecimal("100.00"), "SGD");

    assertThrows(IllegalStateException.class,
        () -> LedgerService.assertBalanced(List.of(dr1, dr2)));
  }
}
