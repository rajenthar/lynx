package com.lynx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Round-trips every event type through JSON, proving the "read eventType first,
 * then dispatch" decode strategy actually reconstructs the correct concrete class.
 */
class EventCodecTest {

  private static final UUID SAGA_ID = UUID.randomUUID();

  @Test
  void roundTripsTransferHeld() {
    UUID account = UUID.randomUUID();
    EventEnvelope<TransferHeld> original = EventEnvelope.of(
        1L, SAGA_ID, "corr-1", new TransferHeld(account, new MoneyAmount(10000, "SGD")));

    EventEnvelope<? extends DomainEvent> decoded = EventCodec.decode(EventCodec.encode(original));

    assertEquals(EventType.TRANSFER_HELD, decoded.eventType());
    assertEquals(original.eventId(), decoded.eventId());
    assertEquals(original.sagaId(), decoded.sagaId());
    assertEquals(original.correlationId(), decoded.correlationId());
    assertEquals(original.occurredAt(), decoded.occurredAt());
    TransferHeld payload = assertInstanceOf(TransferHeld.class, decoded.payload());
    assertEquals(account, payload.accountId());
    assertEquals(new MoneyAmount(10000, "SGD"), payload.amount());
  }

  @Test
  void roundTripsRateLocked() {
    EventEnvelope<RateLocked> original = EventEnvelope.of(
        2L, SAGA_ID, "corr-1", new RateLocked("SGD", "EUR", new BigDecimal("0.6853")));

    EventEnvelope<? extends DomainEvent> decoded = EventCodec.decode(EventCodec.encode(original));

    RateLocked payload = assertInstanceOf(RateLocked.class, decoded.payload());
    assertEquals("SGD", payload.fromCurrency());
    assertEquals("EUR", payload.toCurrency());
    assertEquals(0, new BigDecimal("0.6853").compareTo(payload.rate()));
  }

  @Test
  void roundTripsTransferSettled() {
    UUID source = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    EventEnvelope<TransferSettled> original = EventEnvelope.of(
        3L, SAGA_ID, "corr-1",
        new TransferSettled(source, recipient,
            new MoneyAmount(10000, "SGD"), new MoneyAmount(6853, "EUR")));

    EventEnvelope<? extends DomainEvent> decoded = EventCodec.decode(EventCodec.encode(original));

    TransferSettled payload = assertInstanceOf(TransferSettled.class, decoded.payload());
    assertEquals(source, payload.sourceAccountId());
    assertEquals(recipient, payload.recipientAccountId());
    assertEquals(new MoneyAmount(6853, "EUR"), payload.credited());
  }

  @Test
  void roundTripsTransferFailed() {
    EventEnvelope<TransferFailed> original = EventEnvelope.of(
        4L, SAGA_ID, "corr-1", new TransferFailed("insufficient funds"));

    EventEnvelope<? extends DomainEvent> decoded = EventCodec.decode(EventCodec.encode(original));

    TransferFailed payload = assertInstanceOf(TransferFailed.class, decoded.payload());
    assertEquals("insufficient funds", payload.reason());
  }

  @Test
  void malformedJsonThrowsCodecException() {
    assertThrows(EventCodecException.class, () -> EventCodec.decode("not json"));
  }

  @Test
  void unknownEventTypeThrowsCodecException() {
    String json = """
        {"eventId":1,"sagaId":"%s","correlationId":"c","eventType":"NOT_A_REAL_TYPE",
         "occurredAt":"2026-01-01T00:00:00Z","payload":{}}
        """.formatted(SAGA_ID);
    assertThrows(EventCodecException.class, () -> EventCodec.decode(json));
  }
}
