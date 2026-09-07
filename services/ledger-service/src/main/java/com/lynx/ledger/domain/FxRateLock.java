package com.lynx.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The FX rate a saga locked in during its LOCK phase — one row per saga
 * (`UNIQUE(user_id, saga_id)`), not per ledger leg. Previously stored
 * directly on the {@code LOCK_DR}/{@code LOCK_CR} ledger rows (nullable
 * there, and identically duplicated across both legs); moved here because
 * this is a quote/commitment fact with its own lifecycle, not a
 * money-movement leg — {@link com.lynx.ledger.domain.LedgerEntry} stays
 * exactly what it's documented as (one row = one leg, append-only).
 *
 * <p>{@code userId} replaces what was, earlier in this project, briefly
 * {@code idempotencyKey} — see other-docs/08 Decision 29.
 * ADR-003's rate-lock expiry policy (release, never re-lock the same
 * saga) means {@code lock} now happens AT MOST ONCE per saga, forever, so
 * {@code UNIQUE(user_id, saga_id)} is sufficient on its own — the caller's
 * real identity, not a per-attempt token, is what closes the
 * saga_id-collision gap.
 *
 * <p>Never updated after insert — same reasoning as {@code LedgerEntry}
 * itself.
 */
@Entity
@Table(name = "fx_rate_locks")
public class FxRateLock {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "saga_id", nullable = false)
  private UUID sagaId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "rate", nullable = false, precision = 19, scale = 8)
  private BigDecimal rate;

  @Column(name = "from_currency", nullable = false, length = 3)
  private String fromCurrency;

  @Column(name = "to_currency", nullable = false, length = 3)
  private String toCurrency;

  /**
   * When this locked rate's promise stops being honorable — sourced from
   * fx-rate-service's {@code GET /v1/fx/quotes} response (its TTL), not
   * computed here. Stored and exposed, never enforced by ledger-service
   * itself; deciding what to do with an expired lock is
   * saga-orchestrator's job (pure command-executor principle, unchanged).
   */
  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected FxRateLock() {
    // JPA
  }

  public FxRateLock(UUID sagaId, String userId, BigDecimal rate, String fromCurrency,
                     String toCurrency, Instant expiresAt) {
    this.sagaId = sagaId;
    this.userId = userId;
    this.rate = rate;
    this.fromCurrency = fromCurrency;
    this.toCurrency = toCurrency;
    this.expiresAt = expiresAt;
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

  public BigDecimal getRate() {
    return rate;
  }

  public String getFromCurrency() {
    return fromCurrency;
  }

  public String getToCurrency() {
    return toCurrency;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
