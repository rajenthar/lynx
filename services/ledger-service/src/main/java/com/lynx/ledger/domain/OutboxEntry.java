package com.lynx.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One outbox row (ADR-002). Debezium reads it off the Postgres WAL directly —
 * {@code status}/{@code published_at} are schema placeholders for a future
 * consumer-side view, never written by ledger-service itself in V1.
 *
 * <p>{@code id} uses {@code SEQUENCE} generation, not {@code IDENTITY} —
 * deliberately, so {@link #getId()} is populated the moment {@code
 * save()}/{@code persist()} is called (a cheap {@code nextval()} call),
 * WITHOUT needing an actual {@code INSERT} round-trip first. That id IS the
 * envelope's {@code eventId} (see {@code EventEnvelope} javadoc — it must be
 * the outbox row's own id, so a consumer's {@code last_event_id} guard can
 * compare against it), so it has to be known before the payload can be
 * encoded. An earlier version used {@code IDENTITY} and a save-then-update
 * two-step write to get there — discovered, running this against a REAL
 * Debezium instance, to be a genuine correctness bug: Postgres's logical
 * decoding (pgoutput) replicates each row VERSION as its own change event,
 * so that INSERT (with a placeholder payload) and its following UPDATE
 * arrived as TWO separate Kafka messages sharing the same {@code eventId}
 * — the first one, undecodable, got dead-lettered and marked "already
 * processed" in {@code account-service}'s idempotency table BEFORE the
 * real, correct second message ever arrived, so the correct one was
 * silently skipped as a duplicate. {@code SEQUENCE} generation makes this
 * a single {@code INSERT}, with the correct payload from the very first
 * (and only) row version — one row, one change event.
 *
 * <p>{@code userId} is a plain, unconstrained column — unlike {@code
 * ledger}/{@code fx_rate_locks}, {@code outbox} isn't itself protected by a
 * {@code UNIQUE} constraint (it inherits duplicate-prevention for free: the
 * {@code ledger} insert always runs first in the same transaction and is
 * what actually rejects a retry). It's here purely so a {@code saga_id}
 * collision between two different users' sagas doesn't leave their outbox rows indistinguishable from each other —
 * without it, telling the two apart would mean manually decoding each row's
 * JSON {@code payload} instead of a plain {@code WHERE} filter.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_id_seq")
  @SequenceGenerator(name = "outbox_id_seq", sequenceName = "outbox_id_seq", allocationSize = 1)
  private Long id;

  @Column(name = "saga_id", nullable = false)
  private UUID sagaId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "event_type", nullable = false, length = 30)
  private String eventType;

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
