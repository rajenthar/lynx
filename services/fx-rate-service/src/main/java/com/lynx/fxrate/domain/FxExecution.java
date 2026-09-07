package com.lynx.fxrate.domain;

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
 * One durable record of an FX execution attempt — append-only, never
 * updated after insert (same discipline as {@code LedgerEntry}). {@code
 * executionId} is client-provided and unique: it IS the idempotency key
 * for this write (see {@code FxExecutionService}), no separate
 * {@code Idempotency-Key} header needed since one already exists in the
 * domain (a trade execution request naturally has its own id).
 */
@Entity
@Table(name = "fx_executions")
public class FxExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "execution_id", nullable = false, unique = true)
  private UUID executionId;

  @Column(name = "saga_id", nullable = false)
  private UUID sagaId;

  @Column(name = "amount", nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(name = "from_currency", nullable = false, length = 3)
  private String fromCurrency;

  @Column(name = "to_currency", nullable = false, length = 3)
  private String toCurrency;

  @Column(name = "requested_rate", nullable = false, precision = 19, scale = 8)
  private BigDecimal requestedRate;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 10)
  private FxExecutionStatus status;

  @Column(name = "filled_rate", precision = 19, scale = 8)
  private BigDecimal filledRate;

  @Column(name = "failure_reason", length = 500)
  private String failureReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected FxExecution() {
    // JPA
  }

  private FxExecution(UUID executionId, UUID sagaId, BigDecimal amount, String fromCurrency,
                       String toCurrency, BigDecimal requestedRate, FxExecutionStatus status,
                       BigDecimal filledRate, String failureReason) {
    this.executionId = executionId;
    this.sagaId = sagaId;
    this.amount = amount;
    this.fromCurrency = fromCurrency;
    this.toCurrency = toCurrency;
    this.requestedRate = requestedRate;
    this.status = status;
    this.filledRate = filledRate;
    this.failureReason = failureReason;
    this.createdAt = Instant.now();
  }

  public static FxExecution executed(UUID executionId, UUID sagaId, BigDecimal amount,
                                      String fromCurrency, String toCurrency,
                                      BigDecimal requestedRate, BigDecimal filledRate) {
    return new FxExecution(executionId, sagaId, amount, fromCurrency, toCurrency,
        requestedRate, FxExecutionStatus.EXECUTED, filledRate, null);
  }

  public static FxExecution failed(UUID executionId, UUID sagaId, BigDecimal amount,
                                    String fromCurrency, String toCurrency,
                                    BigDecimal requestedRate, String failureReason) {
    return new FxExecution(executionId, sagaId, amount, fromCurrency, toCurrency,
        requestedRate, FxExecutionStatus.FAILED, null, failureReason);
  }

  public Long getId() {
    return id;
  }

  public UUID getExecutionId() {
    return executionId;
  }

  public UUID getSagaId() {
    return sagaId;
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

  public BigDecimal getRequestedRate() {
    return requestedRate;
  }

  public FxExecutionStatus getStatus() {
    return status;
  }

  public BigDecimal getFilledRate() {
    return filledRate;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
