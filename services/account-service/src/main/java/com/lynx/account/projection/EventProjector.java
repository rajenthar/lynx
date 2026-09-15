package com.lynx.account.projection;

import com.lynx.account.domain.Account;
import com.lynx.account.repository.AccountRepository;
import com.lynx.account.repository.ProcessedEventRepository;
import com.lynx.events.DomainEvent;
import com.lynx.events.EventEnvelope;
import com.lynx.events.FundsDeposited;
import com.lynx.events.RateLocked;
import com.lynx.events.TransferFailed;
import com.lynx.events.TransferHeld;
import com.lynx.events.TransferSettled;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies one decoded event to the balance projection (ADR-005) — the
 * actual "read model" half of CQRS. Idempotent by construction: the
 * "Idempotent Consumer" pattern (other-docs/12 Decision 7) —
 * {@code processed_events} records this exact {@code eventId} exactly
 * once, in the SAME transaction as the balance mutation, so an
 * at-least-once Kafka redelivery is a clean no-op, not a double-applied
 * event. Deliberately NOT a single global high-water-mark (the original
 * design) — see {@code processed_events}' own migration comment for why
 * that assumption breaks across multiple Kafka partitions, and why it's
 * safe to drop here: every adjustment below is an unconditional additive
 * delta, so order was never actually required, only exactly-once application.
 *
 * <p>Five event types, four of which move money (other-docs/12):
 * <ul>
 *   <li>{@link TransferHeld} — {@code available -= amount, held += amount}
 *       on the source account (funds reserved, not yet gone)
 *   <li>{@link TransferSettled} — {@code held -= debited} on the source
 *       (the reservation is now fully consumed), {@code available += credited}
 *       on the recipient (in ITS currency, post-FX-conversion)
 *   <li>{@link TransferFailed} — the compensation path: {@code held -= amount,
 *       available += amount} on the account the funds were released back to
 *   <li>{@link FundsDeposited} — {@code available += amount}, not saga-driven
 *   <li>{@link RateLocked} — ignored; doesn't move money on any account
 * </ul>
 */
public class EventProjector {

  private static final Logger log = LoggerFactory.getLogger(EventProjector.class);

  private final AccountRepository accountRepository;
  private final ProcessedEventRepository processedEventRepository;
  private final TransactionTemplate transactionTemplate;

  public EventProjector(AccountRepository accountRepository,
                         ProcessedEventRepository processedEventRepository,
                         TransactionTemplate transactionTemplate) {
    this.accountRepository = accountRepository;
    this.processedEventRepository = processedEventRepository;
    this.transactionTemplate = transactionTemplate;
  }

  public void apply(EventEnvelope<? extends DomainEvent> envelope) {
    transactionTemplate.executeWithoutResult(status -> {
      int inserted = processedEventRepository.markProcessedIfNew(envelope.eventId());
      if (inserted == 0) {
        log.debug("Skipping already-processed eventId={}", envelope.eventId());
        return;
      }

      DomainEvent event = envelope.payload();
      switch (event) {
        case TransferHeld held -> {
          adjust(held.accountId(), held.amount().toMoney().amount().negate(), held.amount().toMoney().amount());
        }
        case TransferSettled settled -> {
          adjust(settled.sourceAccountId(), BigDecimal.ZERO, settled.debited().toMoney().amount().negate());
          adjustAvailableOnly(settled.recipientAccountId(), settled.credited().toMoney().amount());
        }
        case TransferFailed failed -> {
          adjust(failed.accountId(), failed.amount().toMoney().amount(), failed.amount().toMoney().amount().negate());
        }
        case FundsDeposited deposited -> {
          adjustAvailableOnly(deposited.accountId(), deposited.amount().toMoney().amount());
        }
        case RateLocked ignored -> {
          // Doesn't move money on any account — nothing to project.
        }
      }

      log.info("Applied eventId={} ({}) to the balance projection",
          envelope.eventId(), event.eventType());
    });
  }

  /** {@code TransferHeld}/{@code TransferFailed}: both available and held move, in opposite directions. */
  private void adjust(UUID accountId, BigDecimal availableDelta, BigDecimal heldDelta) {
    Account account = findAccountOrSkip(accountId);
    if (account == null) {
      return;
    }
    Instant now = Instant.now();
    account.applyAvailableDelta(availableDelta, now);
    account.applyHeldDelta(heldDelta, now);
    accountRepository.save(account);
  }

  /** {@code TransferSettled}'s recipient leg / {@code FundsDeposited}: only available moves. */
  private void adjustAvailableOnly(UUID accountId, BigDecimal availableDelta) {
    Account account = findAccountOrSkip(accountId);
    if (account == null) {
      return;
    }
    account.applyAvailableDelta(availableDelta, Instant.now());
    accountRepository.save(account);
  }

  /**
   * An account this projection doesn't know about is logged and skipped,
   * not thrown — a system account (HOLD_POOL/FX_LOCK/FUNDING_SOURCE) never
   * appears in these event fields (confirmed against {@code
   * ledger-service}'s own event construction sites), so in practice this
   * should never fire; skipping rather than failing the whole consumer on
   * one unexpected id is the safer default for a poison-pill message.
   */
  private Account findAccountOrSkip(UUID accountId) {
    return accountRepository.findByIdForUpdate(accountId).orElseGet(() -> {
      log.warn("Event referenced unknown accountId={} — skipping projection update", accountId);
      return null;
    });
  }
}
