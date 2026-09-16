package com.lynx.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row per outbox event this projection has ever applied — the
 * "Idempotent Consumer" pattern, not a single
 * global high-water-mark. See the {@code processed_events} migration's own
 * comment for why: a high-water-mark assumes strictly increasing delivery
 * order, which Kafka only guarantees WITHIN one partition, not across
 * multiple partitions of the same topic.
 *
 * <p>Mutated ONLY through {@link
 * com.lynx.account.repository.ProcessedEventRepository#markProcessedIfNew}
 * — one atomic {@code INSERT ... ON CONFLICT DO NOTHING} statement, never
 * a separate read-then-decide.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

  @Id
  @Column(name = "event_id")
  private long eventId;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  protected ProcessedEvent() {
    // JPA
  }

  public ProcessedEvent(long eventId, Instant processedAt) {
    this.eventId = eventId;
    this.processedAt = processedAt;
  }

  public long getEventId() {
    return eventId;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
