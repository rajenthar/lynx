package com.lynx.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One account = one user = one currency (other-docs/12 — deliberately no
 * multi-currency accounts; a user wanting both SGD and USD creates two
 * rows). {@code available}/{@code held} live directly on this row, not a
 * separate table — the two numbers only ever change together with this
 * row's own identity, no relationship worth normalizing out.
 *
 * <p>This row is NOT the source of truth for either number — {@code
 * ledger-service}'s ledger is (ADR-001). {@code available}/{@code held}
 * are a CQRS projection (ADR-005), mutated ONLY by {@link
 * com.lynx.account.projection.EventProjector} reacting to {@code
 * ledger-service}'s outbox events, never by {@link
 * com.lynx.account.service.AccountService} directly — even a deposit this
 * service itself calls for only takes effect here once its own
 * {@code FundsDeposited} event comes back through the same projection
 * path everything else does.
 */
@Entity
@Table(name = "accounts")
public class Account {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal available;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal held;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Account() {
    // JPA
  }

  public Account(UUID id, String userId, String currency, Instant now) {
    this.id = id;
    this.userId = userId;
    this.currency = currency;
    this.available = BigDecimal.ZERO;
    this.held = BigDecimal.ZERO;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void applyAvailableDelta(BigDecimal delta, Instant now) {
    this.available = this.available.add(delta);
    this.updatedAt = now;
  }

  public void applyHeldDelta(BigDecimal delta, Instant now) {
    this.held = this.held.add(delta);
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getAvailable() {
    return available;
  }

  public BigDecimal getHeld() {
    return held;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
