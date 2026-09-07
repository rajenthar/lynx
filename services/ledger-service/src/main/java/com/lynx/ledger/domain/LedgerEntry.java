package com.lynx.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only double-entry leg. Never updated after insert — deliberately
 * NOT an {@code AuditableEntity} (see that class's javadoc); a plain
 * {@code createdAt} is enough for a row that is written once and never touched
 * again.
 *
 * <p>Deliberately pure money-movement only — no FX-quote fields. The locked
 * rate a {@code LOCK} phase commits to lives in {@link FxRateLock} instead
 * (one row per saga, not duplicated across both legs) — see that class's
 * javadoc for why it doesn't belong here.
 *
 * <p>{@code userId} replaces what was, earlier in this project, a
 * client-supplied {@code idempotencyKey} column — see other-docs/08 Decision 29 for the full
 * reasoning. In short: ADR-003's rate-lock expiry policy (release, never
 * re-lock the same saga) means each phase now happens AT MOST ONCE per saga,
 * forever, so {@code UNIQUE(user_id, saga_id, entry_type)} is sufficient on
 * its own — any repeat call for that exact combination is correctly a
 * retry, never a legitimately new attempt. {@code userId} also closes the
 * saga_id-collision gap directly: it's the caller's real identity, not a
 * per-attempt token that could coincidentally match between two unrelated
 * sagas.
 */
@Entity
@Table(name = "ledger")
public class LedgerEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "saga_id", nullable = false)
  private UUID sagaId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 20)
  private EntryType entryType;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(name = "amount", nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected LedgerEntry() {
    // JPA
  }

  public LedgerEntry(UUID sagaId, String userId, EntryType entryType,
                      UUID accountId, BigDecimal amount, String currency) {
    this.sagaId = sagaId;
    this.userId = userId;
    this.entryType = entryType;
    this.accountId = accountId;
    this.amount = amount;
    this.currency = currency;
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public UUID getSagaId() {
    return sagaId;
  }

  public String getUserId() {
    return userId;
  }

  public EntryType getEntryType() {
    return entryType;
  }

  public UUID getAccountId() {
    return accountId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
