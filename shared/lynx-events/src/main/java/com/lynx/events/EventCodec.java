package com.lynx.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.UUID;

/**
 * JSON (de)serialization for {@link EventEnvelope}s — the outbox {@code payload}
 * column contents / the Kafka message value.
 *
 * <p>Decoding is deliberately explicit rather than relying on Jackson's
 * {@code @JsonTypeInfo} polymorphism: the envelope's own {@code eventType} field
 * is read first, and used to pick which concrete {@link DomainEvent} class to
 * deserialize {@code payload} into. A consumer that has never heard of
 * {@code TransferHeld} at compile time can still read {@code eventType} and
 * dispatch — this is the mechanism ADR-002's "single outbox topic, many event
 * types" design relies on.
 */
public final class EventCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public static String encode(EventEnvelope<? extends DomainEvent> envelope) {
    try {
      return MAPPER.writeValueAsString(envelope);
    } catch (Exception e) {
      throw new EventCodecException("Failed to encode event envelope", e);
    }
  }

  public static EventEnvelope<? extends DomainEvent> decode(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      EventType type = EventType.valueOf(root.get("eventType").asText());
      JsonNode payloadNode = root.get("payload");

      DomainEvent payload = switch (type) {
        case TRANSFER_HELD -> MAPPER.treeToValue(payloadNode, TransferHeld.class);
        case RATE_LOCKED -> MAPPER.treeToValue(payloadNode, RateLocked.class);
        case TRANSFER_SETTLED -> MAPPER.treeToValue(payloadNode, TransferSettled.class);
        case TRANSFER_FAILED -> MAPPER.treeToValue(payloadNode, TransferFailed.class);
      };

      return new EventEnvelope<>(
          root.get("eventId").asLong(),
          UUID.fromString(root.get("sagaId").asText()),
          root.get("correlationId").asText(),
          type,
          Instant.parse(root.get("occurredAt").asText()),
          payload);
    } catch (Exception e) {
      throw new EventCodecException("Failed to decode event envelope", e);
    }
  }

  private EventCodec() {
  }
}
