package com.lynx.ledger.service;

import com.lynx.common.error.ConflictException;
import com.lynx.common.error.NotFoundException;
import com.lynx.events.DomainEvent;
import com.lynx.events.EventCodec;
import com.lynx.events.EventEnvelope;
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
import com.lynx.ledger.dto.LedgerLegView;
import com.lynx.ledger.dto.LedgerPhaseResponse;
import com.lynx.ledger.dto.LockedRateView;
import com.lynx.ledger.repository.FxRateLockRepository;
import com.lynx.ledger.repository.LedgerEntryRepository;
import com.lynx.ledger.repository.OutboxEntryRepository;
import com.lynx.money.Money;
import com.lynx.security.CorrelationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes the four ADR-001 saga phases against the append-only ledger,
 * writing one transactional-outbox event per phase (ADR-002), all wrapped in
 * {@link IdempotencyGuard} (ADR-004).
 *
 * <p>No client-supplied {@code Idempotency-Key} (see other-docs/08 Decision
 * 29): ADR-003's rate-lock expiry policy (release,
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
  private final IdempotencyGuard idempotencyGuard;
  private final TransactionTemplate transactionTemplate;

  public LedgerService(LedgerEntryRepository ledgerEntryRepository,
                        OutboxEntryRepository outboxEntryRepository,
                        FxRateLockRepository fxRateLockRepository,
                        IdempotencyGuard idempotencyGuard,
                        TransactionTemplate transactionTemplate) {
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.outboxEntryRepository = outboxEntryRepository;
    this.fxRateLockRepository = fxRateLockRepository;
    this.idempotencyGuard = idempotencyGuard;
    this.transactionTemplate = transactionTemplate;
  }

  public LedgerPhaseResponse hold(UUID sagaId, String userId, UUID fromAccountId, Money amount) {
    List<LedgerEntry> legs = List.of(
        leg(sagaId, userId, EntryType.HOLD_DR, fromAccountId, amount),
        leg(sagaId, userId, EntryType.HOLD_CR, SystemAccounts.HOLD_POOL, amount));
    TransferHeld event = new TransferHeld(fromAccountId, MoneyAmount.of(amount));
    return writePhase(sagaId, userId, SagaPhase.HOLD, legs, event);
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
   * two reasons as everywhere else in this class (other-docs/08 Decision
   * 30): a {@code saga_id} collision between two unrelated users must not
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
    TransferFailed event = new TransferFailed(reason);
    return writePhase(sagaId, userId, SagaPhase.RELEASE, legs, event);
  }

  /**
   * Which system account still holds this saga's money, determined from
   * what actually happened so far — NOT hardcoded to {@code HOLD_POOL}.
   * Scoped to {@code (sagaId, userId)}, not {@code sagaId} alone — same
   * saga_id-collision defense as everywhere else in this class (other-docs/08
   * Decision 29).
   *
   * <p>Fixes a real bug (other-docs/08 Decision 20): {@code lock} moves
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
   * was a real gap (other-docs/08 Decision 31): on a {@code saga_id}
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
    // client-supplied (see class javadoc / other-docs/08 Decision 29).
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
        withinTransaction.run();

        // The outbox row's own generated id IS the envelope's eventId (see
        // OutboxEntry javadoc), so it must be flushed before the payload can
        // be encoded. eventType comes straight from the event itself — never
        // a separately hand-typed string that could drift out of sync.
        OutboxEntry outboxEntry = new OutboxEntry(sagaId, userId, event.eventType().name());
        outboxEntry = outboxEntryRepository.saveAndFlush(outboxEntry);

        // ADR-004: correlationId is for TRACING only, never idempotency —
        // it must be the request's own correlation id, never sagaId itself.
        // Falls back to a fresh id when nothing set one (e.g. no
        // CorrelationIdFilter in this call path — a unit test, or a future
        // non-HTTP caller) rather than writing a null correlation id.
        String correlationId = Optional.ofNullable(CorrelationId.current())
            .orElseGet(() -> UUID.randomUUID().toString());
        EventEnvelope<DomainEvent> envelope = EventEnvelope.of(
            outboxEntry.getId(), sagaId, correlationId, event);
        outboxEntry.setPayload(EventCodec.encode(envelope));
        outboxEntryRepository.save(outboxEntry);

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
    }
  }

  /**
   * Scoped to {@code phase}'s own two entry types, not just
   * {@code (sagaId, userId)} alone. Since {@code (userId, sagaId,
   * entry_type)} is the whole constraint, a single
   * saga's rows span every phase it's ever gone through — this filter
   * picks out just the phase being recovered, same reasoning as before
   * the {@code userId} rename (other-docs/08 Decision 28), just filtering by
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
}
