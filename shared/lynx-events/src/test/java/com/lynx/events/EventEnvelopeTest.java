package com.lynx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  private static final UUID SAGA_ID = UUID.randomUUID();
  private static final TransferHeld PAYLOAD =
      new TransferHeld(UUID.randomUUID(), new MoneyAmount(10000, "USD"));

  @Test
  void ofDerivesEventTypeFromPayload() {
    EventEnvelope<TransferHeld> envelope =
        EventEnvelope.of(42L, SAGA_ID, "corr-1", PAYLOAD);

    assertEquals(EventType.TRANSFER_HELD, envelope.eventType());
    assertEquals(42L, envelope.eventId());
    assertEquals(SAGA_ID, envelope.sagaId());
  }

  @Test
  void mismatchedEventTypeRejected() {
    // Constructing directly (not via of()) with a type that doesn't match the payload.
    assertThrows(IllegalArgumentException.class, () -> new EventEnvelope<>(
        1L, SAGA_ID, "corr-1", EventType.RATE_LOCKED,
        java.time.Instant.now(), PAYLOAD));
  }

  @Test
  void nullSagaIdRejected() {
    assertThrows(NullPointerException.class,
        () -> EventEnvelope.of(1L, null, "corr-1", PAYLOAD));
  }
}
