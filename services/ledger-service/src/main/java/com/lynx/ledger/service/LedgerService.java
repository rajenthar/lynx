package com.lynx.ledger.service;

import com.lynx.common.error.BusinessRuleException;
import com.lynx.common.error.ConflictException;
import com.lynx.common.error.ErrorCode;
import com.lynx.common.error.NotFoundException;
import com.lynx.events.DomainEvent;
import com.lynx.events.EventCodec;
import com.lynx.events.EventEnvelope;
import com.lynx.events.FundsDeposited;
import com.lynx.events.MoneyAmount;
import com.lynx.events.RateLocked;
import com.lynx.events.TransferFailed;
import com.lynx.events.TransferHeld;
import com.lynx.events.TransferSettled;
import com.lynx.idempotency.DuplicateRequestException;
import com.lynx.idempotency.IdempotencyGuard;
import com.lynx.idempotency.IdempotencyKey;
import com.lynx.ledger.domain.EntryType;
import com.lynx.ledger.domain.FxRateLock;
import com.lynx.ledger.domain.LedgerEntry;
import com.lynx.ledger.domain.OutboxEntry;
import com.lynx.ledger.domain.SagaPhase;
import com.lynx.ledger.domain.SystemAccounts;
import com.lynx.ledger.dto.LedgerHistoryEntryView;
import com.lynx.ledger.dto.LedgerLegView;
import com.lynx.ledger.dto.LedgerPhaseResponse;
import com.lynx.ledger.dto.LockedRateView;
import com.lynx.ledger.repository.AccountBalanceRepository;
import com.lynx.ledger.repository.FxRateLockRepository;
import com.lynx.ledger.repository.LedgerEntryRepository;
import com.lynx.ledger.repository.OutboxEntryRepository;
import com.lynx.money.Money;
import com.lynx.security.CorrelationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes the four ADR-001 saga phases against the append-only ledger,
 * writing one transactional-outbox event per phase (ADR-002), all wrapped in
 * {@link IdempotencyGuard} (ADR-004).
 *
 * <p>No client-supplied {@code Idempotency-Key}: ADR-003's rate-lock expiry policy (release,
 * never re-lock the same saga) means each phase now happens AT MOST ONCE
 * per saga, forever — {@code (userId, sagaId, phase)} is a complete,
 * sufficient identity on its own. {@link IdempotencyGuard} still runs
 * identically underneath; it's fed a key deterministically derived from
 * {@code sagaId} itself (see {@link #writePhase}) rather than one
 * supplied by the caller.
 *
 * <p>Each phase's transactional write happens via {@link TransactionTemplate}
 * rather than a {@code @Transactional} method, because the write itself runs
 * from inside a lambda passed to {@code IdempotencyGuard.execute} — a
 * same-class self-invocation of an {@code @Transactional} method would bypass
 * Spring's proxy and silently run non-transactionally.
 */
@Service
public class LedgerService {

  private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

  private final LedgerEntryRepository ledgerEntryRepository;
  private final OutboxEntryRepository outboxEntryRepository;
  private final FxRateLockRepository fxRateLockRepository;
  private final AccountBalanceRepository accountBalanceRepository;
  private final IdempotencyGuard idempotencyGuard;
  private final TransactionTemplate transactionTemplate;

  public LedgerService(LedgerEntryRepository ledgerEntryRepository,
                        OutboxEntryRepository outboxEntryRepository,
                        FxRateLockRepository fxRateLockRepository,
                        AccountBalanceRepository accountBalanceRepository,
                        IdempotencyGuard idempotencyGuard,
                        TransactionTemplate transactionTemplate) {
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.outboxEntryRepository = outboxEntryRepository;
    this.fxRateLockRepository = fxRateLockRepository;
    this.accountBalanceRepository = accountBalanceRepository;
    this.idempotencyGuard = idempotencyGuard;
    this.transactionTemplate = transactionTemplate;
  }

  /**
   * The account must actually be able to cover {@code amount} before the
   * {@code HOLD_DR} leg is allowed to post —
   * enforced by {@link #insertLegsAndOutbox}'s per-leg balance application,
   * NOT a separate read-then-decide step here. See
   * {@link AccountBalanceRepository#debitIfSufficient} for why: the
   * industry-standard pattern (TigerBeetle's running balance fields;
   * Modern Treasury's "post only if the resulting balance satisfies a
   * range") is one atomic conditional {@code UPDATE}, not a plain
   * transaction wrapping a {@code SUM(...)} check — the latter is STILL
   * racy under concurrent holds on the same account even inside one
   * transaction, at the database's default isolation level.
   */
  public LedgerPhaseResponse hold(UUID sagaId, String userId, UUID fromAccountId, Money amount) {
    List<LedgerEntry> legs = List.of(
        leg(sagaId, userId, EntryType.HOLD_DR, fromAccountId, amount),
        leg(sagaId, userId, EntryType.HOLD_CR, SystemAccounts.HOLD_POOL, amount));
    TransferHeld event = new TransferHeld(fromAccountId, MoneyAmount.of(amount));
    return writePhase(sagaId, userId, SagaPhase.HOLD, legs, event);
  }

  /**
   * Not saga-driven — a direct, atomic double-entry credit
   * from {@link SystemAccounts#FUNDING_SOURCE}. {@code depositId} plays
   * exactly the role {@code sagaId} plays for the four saga phases: a
   * caller-supplied UUID that IS the write identity, so a retried deposit
   * request is a no-op the same way a retried saga phase is. No balance
   * check needed — crediting an account can never overdraw it.
   */
  public LedgerPhaseResponse deposit(UUID depositId, String userId, UUID accountId, Money amount) {
    List<LedgerEntry> legs = List.of(
        leg(depositId, userId, EntryType.DEPOSIT_DR, SystemAccounts.FUNDING_SOURCE, amount),
        leg(depositId, userId, EntryType.DEPOSIT_CR, accountId, amount));
    FundsDeposited event = new FundsDeposited(accountId, MoneyAmount.of(amount));
    return writePhase(depositId, userId, SagaPhase.DEPOSIT, legs, event);
  }

  public LedgerPhaseResponse lock(UUID sagaId, String userId, Money lockedAmount,
                                   String fromCurrency, String toCurrency, BigDecimal rate,
                                   Instant expiresAt) {
    List<LedgerEntry> legs = List.of(
        leg(sagaId, userId, EntryType.LOCK_DR, SystemAccounts.HOLD_POOL, lockedAmount),
        leg(sagaId, userId, EntryType.LOCK_CR, SystemAccounts.FX_LOCK, lockedAmount));
    RateLocked event = new RateLocked(fromCurrency, toCurrency, rate);
    // Persisted as its own row (one per saga), not on the legs themselves —
    // see FxRateLock javadoc; written in the SAME transaction as the legs
    // and outbox insert below, so SETTLE (a later, separate request) always
    // has a queryable answer to "which rate did this saga lock in" the
    // moment LOCK's response comes back. expiresAt comes from whatever
    // quoted this rate (fx-rate-service's GET /v1/fx/quotes) — not computed
    // here; ledger-service stores/exposes it but never enforces it itself.
    return writePhase(sagaId, userId, SagaPhase.LOCK, legs, event,
        () -> fxRateLockRepository.save(
            new FxRateLock(sagaId, userId, rate, fromCurrency, toCurrency, expiresAt)));
  }

  /**
   * The FX rate this saga locked in during its LOCK phase — read back from
   * {@link FxRateLock}, not decoded out of the LOCK phase's outbox payload,
   * for a caller (e.g. an orchestrator) that needs it to compute SETTLE's
   * debited/credited amounts on a later, separate request.
   *
   * <p>Scoped to {@code (sagaId, userId)}, not {@code sagaId} alone — same
   * two reasons as everywhere else in this class: a {@code saga_id} collision between two unrelated users must not
   * return the wrong one's locked rate, and a caller has no business
   * reading a rate for a saga that isn't theirs in the first place.
   *
   * @throws NotFoundException 404 if this saga never reached LOCK for this
   *     user (also the response for a saga that belongs to someone else —
   *     same as a genuinely nonexistent saga, so this endpoint can't be
   *     used to probe for other users' saga ids)
   */
  public LockedRateView lockedRate(UUID sagaId, String userId) {
    FxRateLock lock = fxRateLockRepository.findBySagaIdAndUserId(sagaId, userId)
        .orElseThrow(() -> {
          log.warn("No LOCK phase recorded for saga {}, userId={} — rate lookup returning 404",
              sagaId, userId);
          return new NotFoundException("No LOCK phase recorded for saga " + sagaId);
        });
    return new LockedRateView(
        lock.getRate(), lock.getFromCurrency(), lock.getToCurrency(), lock.getExpiresAt());
  }

  public LedgerPhaseResponse settle(UUID sagaId, String userId, UUID fromAccountId,
                                     UUID toAccountId, Money debited, Money credited) {
    List<LedgerEntry> legs = List.of(
        leg(sagaId, userId, EntryType.SETTLE_DR, SystemAccounts.FX_LOCK, debited),
        leg(sagaId, userId, EntryType.SETTLE_CR, toAccountId, credited));
    TransferSettled event = new TransferSettled(
        fromAccountId, toAccountId, MoneyAmount.of(debited), MoneyAmount.of(credited));
    return writePhase(sagaId, userId, SagaPhase.SETTLE, legs, event);
  }

  public LedgerPhaseResponse release(UUID sagaId, String userId, UUID accountId, Money amount,
                                      String reason) {
    UUID sourcePool = releaseSourcePool(sagaId, userId);
    List<LedgerEntry> legs = List.of(
        leg(sagaId, userId, EntryType.RELEASE_DR, sourcePool, amount),
        leg(sagaId, userId, EntryType.RELEASE_CR, accountId, amount));
    TransferFailed event = new TransferFailed(accountId, MoneyAmount.of(amount), reason);
    return writePhase(sagaId, userId, SagaPhase.RELEASE, legs, event);
  }

  /**
   * Which system account still holds this saga's money, determined from
   * what actually happened so far — NOT hardcoded to {@code HOLD_POOL}.
   * Scoped to {@code (sagaId, userId)}, not {@code sagaId} alone — same
   * saga_id-collision defense as everywhere else in this class.
   *
   * <p>Fixes a real bug: {@code lock} moves
   * money OUT of {@code HOLD_POOL} and INTO {@code FX_LOCK}
   * ({@code LOCK_DR(HOLD_POOL)}/{@code LOCK_CR(FX_LOCK)}). A
   * {@code release} that always reversed {@code HOLD_POOL} would, for a
   * saga that failed AFTER a successful {@code lock}, debit an account the
   * money had already left — silently driving {@code HOLD_POOL} negative
   * and leaving {@code FX_LOCK} permanently stuck, even though the sender
   * still gets their money back and nothing else looks wrong.
   *
   * @throws ConflictException 409 if {@code settle} already completed for
   *     this saga — the money has already left {@code FX_LOCK} for the
   *     recipient, so there is nothing left in either system account for
   *     {@code release} to reverse
   */
  private UUID releaseSourcePool(UUID sagaId, String userId) {
    List<LedgerEntry> existing = ledgerEntryRepository.findBySagaIdAndUserIdOrderByCreatedAtAsc(sagaId, userId);
    boolean settled = existing.stream().anyMatch(e -> e.getEntryType() == EntryType.SETTLE_DR);
    if (settled) {
      log.warn("Refusing release for saga {}: settle already completed, nothing left to reverse", sagaId);
      throw new ConflictException(
          "Cannot release saga " + sagaId + ": settle already completed — "
              + "funds already transferred to the recipient, nothing left to reverse");
    }
    boolean locked = existing.stream().anyMatch(e -> e.getEntryType() == EntryType.LOCK_CR);
    UUID sourcePool = locked ? SystemAccounts.FX_LOCK : SystemAccounts.HOLD_POOL;
    log.info("Releasing saga {} from {}", sagaId, locked ? "FX_LOCK" : "HOLD_POOL");
    return sourcePool;
  }

  /**
   * Scoped to {@code (sagaId, userId)}, not {@code sagaId} alone — this
   * was a real gap: on a {@code saga_id}
   * collision between two unrelated users, an unscoped query would have
   * returned BOTH users' legs mixed into one response — actual cross-user
   * financial data in a live API response, not just an internal-tooling
   * inconvenience like the {@code outbox} case. It also closes a plain
   * missing-authorization gap this endpoint had regardless of collisions:
   * without {@code userId} scoping, any authenticated caller who knew (or
   * guessed) a {@code sagaId} could read its full audit trail, whether or
   * not it was theirs.
   */
  public List<LedgerLegView> auditTrail(UUID sagaId, String userId) {
    return ledgerEntryRepository.findBySagaIdAndUserIdOrderByCreatedAtAsc(sagaId, userId).stream()
        .map(LedgerService::toView)
        .toList();
  }

  /**
   * Everything that has ever happened to one account —
   * deposits, holds, settles, releases, spanning every saga/deposit that
   * ever touched it, newest first. Unlike {@link #auditTrail}, NOT scoped
   * by {@code userId}: a ledger row's own {@code userId} is who initiated
   * THAT operation (e.g. the sender for a {@code SETTLE_CR} row crediting
   * the RECIPIENT's account), not who owns the account being queried —
   * {@code ledger-service} has no notion of account ownership at all
   * (that's {@code account-service}'s data). Callers that
   * need "is this actually the caller's own account" enforced must check
   * that themselves before calling this — this endpoint trusts its caller
   * the same way every {@code ledger-service} write already does.
   */
  public List<LedgerHistoryEntryView> accountHistory(UUID accountId, int limit) {
    Pageable page = PageRequest.of(0, limit);
    return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, page).stream()
        .map(LedgerService::toHistoryView)
        .toList();
  }

  private LedgerPhaseResponse writePhase(UUID sagaId, String userId, SagaPhase phase,
                                          List<LedgerEntry> legs, DomainEvent event) {
    return writePhase(sagaId, userId, phase, legs, event, () -> { });
  }

  /**
   * @param withinTransaction extra persistence work that must commit
   *     atomically with the legs/outbox insert — e.g. LOCK's
   *     {@code FxRateLock} row. A no-op for every phase that doesn't need one.
   */
  private LedgerPhaseResponse writePhase(UUID sagaId, String userId, SagaPhase phase,
                                          List<LedgerEntry> legs, DomainEvent event,
                                          Runnable withinTransaction) {
    // sagaId itself is the idempotency key now — deterministic, not
    // client-supplied (see class javadoc).
    IdempotencyKey derivedKey = IdempotencyKey.derivedFrom(sagaId.toString());
    log.debug("Entering {} for saga {}, userId={}", phase, sagaId, userId);
    return idempotencyGuard.execute(
        userId,
        derivedKey,
        phase.name(),
        LedgerPhaseResponse.class,
        () -> insertLegsAndOutbox(sagaId, userId, legs, event, withinTransaction),
        () -> recoverFromDb(sagaId, userId, phase));
  }

  private LedgerPhaseResponse insertLegsAndOutbox(UUID sagaId, String userId, List<LedgerEntry> legs,
                                                    DomainEvent event, Runnable withinTransaction) {
    assertBalanced(legs);
    try {
      return transactionTemplate.execute(status -> {
        List<LedgerEntry> saved = ledgerEntryRepository.saveAll(legs);
        applyBalanceDeltas(legs);
        withinTransaction.run();

        // The outbox row's own id IS the envelope's eventId (see OutboxEntry
        // javadoc), so it must be known before the payload can be encoded —
        // SEQUENCE generation assigns it right here, on save(), WITHOUT
        // needing an actual INSERT round-trip yet (unlike the IDENTITY
        // strategy this used to use). eventType comes straight from the
        // event itself — never a separately hand-typed string that could
        // drift out of sync.
        OutboxEntry outboxEntry = new OutboxEntry(sagaId, userId, event.eventType().name());
        outboxEntry = outboxEntryRepository.save(outboxEntry);

        // ADR-004: correlationId is for TRACING only, never idempotency —
        // it must be the request's own correlation id, never sagaId itself.
        // Falls back to a fresh id when nothing set one (e.g. no
        // CorrelationIdFilter in this call path — a unit test, or a future
        // non-HTTP caller) rather than writing a null correlation id.
        String correlationId = Optional.ofNullable(CorrelationId.current())
            .orElseGet(() -> UUID.randomUUID().toString());
        EventEnvelope<DomainEvent> envelope = EventEnvelope.of(
            outboxEntry.getId(), sagaId, correlationId, event);
        // Not yet flushed — this and the save() above collapse into ONE
        // single INSERT at transaction commit (see OutboxEntry's own
        // javadoc for why a second, separate write here would be a real
        // CDC correctness bug, not just an extra round trip).
        outboxEntry.setPayload(EventCodec.encode(envelope));

        log.info("Wrote {} leg(s) for saga {} ({}), outbox eventId={}",
            saved.size(), sagaId, event.eventType(), outboxEntry.getId());
        return new LedgerPhaseResponse(sagaId, saved.stream().map(LedgerService::toView).toList());
      });
    } catch (DataIntegrityViolationException e) {
      // Expected on a genuine retry — the UNIQUE(user_id, saga_id, entry_type)
      // constraint is doing exactly its job. warn, not error: IdempotencyGuard
      // catches this and recovers the original below; this is the normal
      // "same saga, same phase, called again" path, not a real failure.
      log.warn("UNIQUE constraint rejected insert for saga {} — treating as a duplicate, "
          + "recovering the original", sagaId, e);
      throw new DuplicateRequestException(
          "Ledger entry already exists for saga " + sagaId, e);
    } catch (CannotAcquireLockException e) {
      // A genuine deadlock between two concurrent writes that each locked
      // TWO different account_balances rows in opposite orders (e.g. two
      // different transfers both touching the same pair of accounts) —
      // Postgres detects and kills one participant. Rare, but possible
      // with row-level locking whenever a
      // single write touches more than one account row. NOTHING was
      // persisted — safe to retry, same reasoning as the UNIQUE-constraint
      // case above being a genuine duplicate rather than a real failure.
      // Surfaced as DOWNSTREAM_UNAVAILABLE (503) deliberately:
      // saga-orchestrator's AbstractServiceClient already treats a 5xx as
      // transient and retries the same phase via its own polling loop
      // (ADR-003) — reusing that existing retry path instead of retrying
      // internally here.
      log.warn("Deadlock writing saga {} — nothing persisted, safe to retry", sagaId, e);
      throw new BusinessRuleException(ErrorCode.DOWNSTREAM_UNAVAILABLE,
          "Concurrent write conflict for saga " + sagaId + " — retry the same request");
    }
  }

  /**
   * Applies every leg's balance delta to {@code account_balances}
   * — the materialized running balance this
   * whole mechanism replaced a {@code SUM(...)}-over-history check with.
   * Every {@code *_CR} leg credits (positive delta), every {@code *_DR}
   * leg debits (negative delta) — same convention {@link #assertBalanced}
   * already relies on. Only {@link EntryType#HOLD_DR} — a REAL account
   * being debited, where insufficient funds must actually reject the
   * write — goes through the GUARDED conditional update; every other leg
   * (including every credit, and every debit from a system account, which
   * is allowed to run negative) is unconditional.
   *
   * <p><b>Lock ordering:</b> sorted by {@code
   * accountId} before applying, NOT in each phase's own construction order
   * (e.g. {@code hold()} builds {@code [realAccount, HOLD_POOL]}, {@code
   * release()} builds {@code [sourcePool, realAccount]} — reversed). Two
   * legs sharing the same pair of {@code account_balances} rows (a
   * {@code HOLD} on some account racing a {@code RELEASE} back into that
   * same account, both pivoting through the shared {@code HOLD_POOL} row)
   * would otherwise be able to acquire those two row-locks in opposite
   * order — the textbook precondition for a deadlock. Forcing a single,
   * global, deterministic order (lowest {@code accountId} first) across
   * every phase closes it: this is the standard "lock ordering" /
   * "resource ordering" deadlock-prevention technique — see e.g.
   * <a href="https://wiki.sei.cmu.edu/confluence/display/java/LCK07-J.+Avoid+deadlock+by+requesting+and+releasing+locks+in+the+same+order">CERT
   * LCK07-J</a>. Safe to reorder: every delta below is an independent,
   * commutative addition to its own row — changing which row gets locked
   * first changes nothing about the final balances, only the lock
   * acquisition order.
   */
  private void applyBalanceDeltas(List<LedgerEntry> legs) {
    List<LedgerEntry> lockOrdered = legs.stream()
        .sorted(Comparator.comparing(LedgerEntry::getAccountId))
        .toList();
    for (LedgerEntry leg : lockOrdered) {
      if (leg.getEntryType() == EntryType.HOLD_DR) {
        int updated = accountBalanceRepository.debitIfSufficient(
            leg.getAccountId(), leg.getCurrency(), leg.getAmount());
        if (updated == 0) {
          throw BusinessRuleException.insufficientFunds(
              leg.getAccountId().toString(), leg.getCurrency());
        }
      } else {
        BigDecimal delta = leg.getEntryType().name().endsWith("_CR")
            ? leg.getAmount() : leg.getAmount().negate();
        accountBalanceRepository.adjustUnconditionally(leg.getAccountId(), leg.getCurrency(), delta);
      }
    }
  }

  /**
   * Scoped to {@code phase}'s own two entry types, not just
   * {@code (sagaId, userId)} alone. Since {@code (userId, sagaId,
   * entry_type)} is the whole constraint, a single
   * saga's rows span every phase it's ever gone through — this filter
   * picks out just the phase being recovered, same reasoning as before
   * the {@code userId} rename, just filtering by
   * {@code userId} now instead of a per-attempt key.
   */
  Optional<LedgerPhaseResponse> recoverFromDb(UUID sagaId, String userId, SagaPhase phase) {
    List<LedgerEntry> existing = ledgerEntryRepository
        .findBySagaIdAndUserIdOrderByCreatedAtAsc(sagaId, userId)
        .stream()
        .filter(entry -> entry.getEntryType().name().startsWith(phase.name() + "_"))
        .toList();
    if (existing.isEmpty()) {
      // Reached only when the DB just rejected a duplicate insert (caller above
      // caught DuplicateRequestException) yet this exact query finds nothing —
      // the original attempt's transaction hasn't committed yet. Not a bug by
      // itself (IdempotencyGuard turns this into a 409 "retry shortly"), but
      // worth a log line since a caller seeing repeated 409s for one saga+phase
      // could indicate the original attempt is stuck, not just slow.
      log.info("No committed {} rows yet for saga {}, userId={} — original attempt "
          + "still in flight, caller will get 409", phase, sagaId, userId);
      return Optional.empty();
    }
    return Optional.of(new LedgerPhaseResponse(
        sagaId, existing.stream().map(LedgerService::toView).toList()));
  }

  /**
   * ADR-001's double-entry invariant: exactly one DR and one CR leg per
   * phase. For HOLD/LOCK/RELEASE, both legs also share the same
   * amount/currency (a same-currency clearing move); SETTLE is deliberately
   * exempt from that equality — its DR/CR amounts differ by design (FX
   * conversion across two different currencies, per {@code TransferSettled}).
   *
   * <p>Finds the DR/CR legs by their actual {@code entry_type} suffix, not
   * by list position — every call site happens to build {@code legs} as
   * {@code List.of(drLeg, crLeg)}, but this method doesn't rely on that
   * ordering being preserved. Relying on position was a real risk: getting
   * it wrong would have been harmless for HOLD/LOCK/RELEASE (both legs
   * share amount/currency regardless of order) but NOT for SETTLE — a
   * swapped order would make {@code legs.get(0)} report a {@code
   * SETTLE_CR} type, wrongly conclude "same-currency clearing move," and
   * throw a false invariant violation on a completely legitimate FX
   * settlement.
   *
   * <p>Package-private, for testing — same reasoning as {@link
   * #recoverFromDb}: the narrowest visibility change that makes this
   * internal edge case directly testable without going through the public
   * hold/lock/settle/release API (which never actually produces a
   * reversed-order {@code legs} list itself; this method just no longer
   * assumes it never will).
   */
  static void assertBalanced(List<LedgerEntry> legs) {
    if (legs.size() != 2) {
      log.error("Invariant violation: expected exactly 2 legs (one DR, one CR), got {}: {}",
          legs.size(), legs);
      throw new IllegalStateException("Expected exactly 2 legs (one DR, one CR), got " + legs.size());
    }
    LedgerEntry dr = legs.stream().filter(l -> l.getEntryType().name().endsWith("_DR")).findFirst()
        .orElseThrow(() -> {
          log.error("Invariant violation: no DR leg found: {}", legs);
          return new IllegalStateException("No DR leg found: " + legs);
        });
    LedgerEntry cr = legs.stream().filter(l -> l.getEntryType().name().endsWith("_CR")).findFirst()
        .orElseThrow(() -> {
          log.error("Invariant violation: no CR leg found: {}", legs);
          return new IllegalStateException("No CR leg found: " + legs);
        });
    boolean sameCurrencyClearingMove = dr.getEntryType() != EntryType.SETTLE_DR;
    if (sameCurrencyClearingMove) {
      if (dr.getAmount().compareTo(cr.getAmount()) != 0
          || !dr.getCurrency().equals(cr.getCurrency())) {
        log.error("Invariant violation: DR/CR legs are not balanced: {}", legs);
        throw new IllegalStateException("DR/CR legs are not balanced: " + legs);
      }
    }
  }

  private static LedgerEntry leg(UUID sagaId, String userId, EntryType type,
                                  UUID accountId, Money amount) {
    return new LedgerEntry(sagaId, userId, type, accountId, amount.amount(), amount.currencyCode());
  }

  private static LedgerLegView toView(LedgerEntry entry) {
    return new LedgerLegView(
        entry.getEntryType(), entry.getAccountId(), entry.getAmount(), entry.getCurrency());
  }

  private static LedgerHistoryEntryView toHistoryView(LedgerEntry entry) {
    return new LedgerHistoryEntryView(entry.getSagaId(), entry.getEntryType(), entry.getAccountId(),
        entry.getAmount(), entry.getCurrency(), entry.getCreatedAt());
  }
}
