package com.lynx.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The one row this whole service exists to drive forward (ADR-003,
 * other-docs/10). Unlike {@code LedgerEntry}/{@code OutboxEntry}
 * (append-only, never updated), this row IS mutated repeatedly across a
 * saga's lifetime — {@code status} advances HOLDING → LOCKED → EXECUTED →
 * SETTLED, or diverts to FAILED at any point.
 *
 * <p>{@code executionId} is computed once, deterministically, the moment a
 * saga reaches {@code LOCKED}, and reused verbatim on every redo of the
 * EXECUTE step for this saga — the exact redo-safety discipline ADR-004
 * originally described for {@code ledger-service}'s own (now-removed)
 * client {@code Idempotency-Key}, relocated here because
 * {@code fx-rate-service}'s {@code POST /v1/fx/executions} still uses a
 * real client-supplied key (its {@code executionId}) as its own idempotency
 * key. A fresh {@code executionId} on every redo would let a stuck/crashed
 * EXECUTE step's retry create a genuinely SECOND execution.
 *
 * <p>{@code @Version} adds JPA optimistic locking as a second, belt-and-
 * braces layer underneath the {@code FOR UPDATE SKIP LOCKED} pessimistic
 * claim ({@code SagaStateRepository}) — the claim already prevents two
 * orchestrator instances from processing the same row concurrently, so
 * this should never actually fire in practice; it exists purely so a bug
 * that somehow let two transactions touch the same row raises a loud,
 * immediate {@code OptimisticLockException} instead of silently losing an
 * update.
 *
 * <p>Primary key is a surrogate {@code id}, NOT {@code sagaId} — found by
 * direct inspection (other-docs/10 Decision 7). {@code sagaId} is
 * deterministically derived upstream (by a future {@code
 * transaction-service}, from a real client's own {@code Idempotency-Key}
 * — ADR-004), so it can theoretically collide between two unrelated
 * users, same reasoning already applied to {@code ledger}/{@code
 * fx_rate_locks}. It would have been WORSE here than in those tables: with
 * {@code sagaId} as a plain single-column primary key, two different
 * users could never even coexist as separate rows on a collision — the
 * second caller's {@code createSaga} would silently return the FIRST
 * user's row as if it were its own, never actually holding the second
 * user's funds at all. {@code UNIQUE(user_id, saga_id)} below is what
 * {@code ledger}/{@code fx_rate_locks} both already do — a collision
 * becomes a harmless, distinguishable extra row instead of silent
 * misrouting.
 */
@Entity
@Table(name = "saga_state")
public class SagaState {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "saga_id", nullable = false)
  private UUID sagaId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private SagaStatus status;

  @Column(name = "from_account_id", nullable = false)
  private UUID fromAccountId;

  @Column(name = "to_account_id", nullable = false)
  private UUID toAccountId;

  @Column(name = "amount", nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(name = "from_currency", nullable = false, length = 3)
  private String fromCurrency;

  @Column(name = "to_currency", nullable = false, length = 3)
  private String toCurrency;

  @Column(name = "rate", precision = 19, scale = 8)
  private BigDecimal rate;

  @Column(name = "rate_expires_at")
  private Instant rateExpiresAt;

  @Column(name = "execution_id")
  private UUID executionId;

  @Column(name = "filled_rate", precision = 19, scale = 8)
  private BigDecimal filledRate;

  @Column(name = "failure_reason", length = 500)
  private String failureReason;

  @Column(name = "retried_from_saga_id")
  private UUID retriedFromSagaId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected SagaState() {
    // JPA
  }

  public SagaState(UUID sagaId, String userId, UUID fromAccountId, UUID toAccountId,
                    BigDecimal amount, String fromCurrency, String toCurrency,
                    UUID retriedFromSagaId) {
    this.sagaId = sagaId;
    this.userId = userId;
    this.status = SagaStatus.HOLDING;
    this.fromAccountId = fromAccountId;
    this.toAccountId = toAccountId;
    this.amount = amount;
    this.fromCurrency = fromCurrency;
    this.toCurrency = toCurrency;
    this.retriedFromSagaId = retriedFromSagaId;
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
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

  public SagaStatus getStatus() {
    return status;
  }

  public void setStatus(SagaStatus status) {
    this.status = status;
    this.updatedAt = Instant.now();
  }

  public UUID getFromAccountId() {
    return fromAccountId;
  }

  public UUID getToAccountId() {
    return toAccountId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getFromCurrency() {
    return fromCurrency;
  }

  public String getToCurrency() {
    return toCurrency;
  }

  public BigDecimal getRate() {
    return rate;
  }

  public void setRate(BigDecimal rate) {
    this.rate = rate;
  }

  public Instant getRateExpiresAt() {
    return rateExpiresAt;
  }

  public void setRateExpiresAt(Instant rateExpiresAt) {
    this.rateExpiresAt = rateExpiresAt;
  }

  public UUID getExecutionId() {
    return executionId;
  }

  public void setExecutionId(UUID executionId) {
    this.executionId = executionId;
  }

  public BigDecimal getFilledRate() {
    return filledRate;
  }

  public void setFilledRate(BigDecimal filledRate) {
    this.filledRate = filledRate;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }

  public UUID getRetriedFromSagaId() {
    return retriedFromSagaId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
