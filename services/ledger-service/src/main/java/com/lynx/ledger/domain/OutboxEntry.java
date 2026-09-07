package com.lynx.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One outbox row (ADR-002). Debezium reads it off the Postgres WAL directly —
 * {@code status}/{@code published_at} are schema placeholders for a future
 * consumer-side view, never written by ledger-service itself in V1.
 *
 * <p>{@code payload} is filled in two steps: the row is first inserted to
 * obtain its own generated {@code id}, because that id IS the envelope's
 * {@code eventId} (see {@code EventEnvelope} javadoc — it must be the outbox
 * row's own BIGSERIAL id, so a consumer's {@code last_event_id} guard can
 * compare against it). The payload is set afterwards and flushed as a single
 * UPDATE within the same transaction. Not an {@code AuditableEntity}: this row
 * is never updated after that one payload backfill.
 *
 * <p>{@code userId} is a plain, unconstrained column — unlike {@code
 * ledger}/{@code fx_rate_locks}, {@code outbox} isn't itself protected by a
 * {@code UNIQUE} constraint (it inherits duplicate-prevention for free: the
 * {@code ledger} insert always runs first in the same transaction and is
 * what actually rejects a retry). It's here purely so a {@code saga_id}
 * collision between two different users' sagas (see other-docs/08 Decision
 * 29) doesn't leave their outbox rows indistinguishable from each other —
 * without it, telling the two apart would mean manually decoding each row's
 * JSON {@code payload} instead of a plain {@code WHERE} filter.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "saga_id", nullable = false)
  private UUID sagaId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "event_type", nullable = false, length = 30)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected OutboxEntry() {
    // JPA
  }

  public OutboxEntry(UUID sagaId, String userId, String eventType) {
    this.sagaId = sagaId;
    this.userId = userId;
    this.eventType = eventType;
    this.payload = "{}";
    this.createdAt = Instant.now();
  }

  public void setPayload(String payload) {
    this.payload = payload;
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

  public String getEventType() {
    return eventType;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
