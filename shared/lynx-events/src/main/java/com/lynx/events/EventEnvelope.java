package com.lynx.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The outbox/Kafka message shape wrapping a {@link DomainEvent} (ADR-002).
 *
 * <p>Mirrors the outbox table columns directly:
 * <pre>
 *   outbox(id, saga_id, event_type, payload, created_at)
 *          |   |         |           |        |
 *          eventId  sagaId   eventType  payload  occurredAt
 * </pre>
 *
 * <p>{@code eventId} is the outbox row's {@code BIGSERIAL id} — monotonic
 * per partition (partition key = sagaId, ADR-002), and it IS the value
 * account-service's {@code last_event_id} guard compares against (ADR-005):
 * {@code WHERE last_event_id < :eventId} makes redelivery a no-op.
 *
 * <p>{@code correlationId} is for distributed tracing ONLY (ADR-004) — never
 * used for deduplication; that job belongs entirely to {@code eventId}.
 */
public record EventEnvelope<T extends DomainEvent>(
    long eventId,
    UUID sagaId,
    String correlationId,
    EventType eventType,
    Instant occurredAt,
    T payload
) {

  public EventEnvelope {
    Objects.requireNonNull(sagaId, "sagaId");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (eventType == null) {
      eventType = payload.eventType();
    } else if (eventType != payload.eventType()) {
      throw new IllegalArgumentException(
          "eventType " + eventType + " does not match payload's own type "
              + payload.eventType());
    }
  }

  /** Construct with {@code occurredAt = now} and {@code eventType} derived from the payload. */
  public static <T extends DomainEvent> EventEnvelope<T> of(
      long eventId, UUID sagaId, String correlationId, T payload) {
    return new EventEnvelope<>(eventId, sagaId, correlationId, payload.eventType(),
        Instant.now(), payload);
  }
}
