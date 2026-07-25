package com.lynx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** One happy-path + one rejection per event, covering the sealed hierarchy. */
class DomainEventTest {

  private static final MoneyAmount POSITIVE = new MoneyAmount(10000, "USD");
  private static final MoneyAmount ZERO = new MoneyAmount(0, "USD");

  @Test
  void transferHeldCarriesItsOwnType() {
    TransferHeld event = new TransferHeld(UUID.randomUUID(), POSITIVE);
    assertEquals(EventType.TRANSFER_HELD, event.eventType());
  }

  @Test
  void transferHeldRejectsNonPositiveAmount() {
    assertThrows(IllegalArgumentException.class,
        () -> new TransferHeld(UUID.randomUUID(), ZERO));
  }

  @Test
  void rateLockedCarriesItsOwnType() {
    RateLocked event = new RateLocked("SGD", "EUR", new BigDecimal("0.6853"));
    assertEquals(EventType.RATE_LOCKED, event.eventType());
  }

  @Test
  void rateLockedRejectsNonPositiveRate() {
    assertThrows(IllegalArgumentException.class,
        () -> new RateLocked("SGD", "EUR", BigDecimal.ZERO));
  }

  @Test
  void transferSettledCarriesItsOwnType() {
    TransferSettled event = new TransferSettled(
        UUID.randomUUID(), UUID.randomUUID(), POSITIVE, new MoneyAmount(6853, "EUR"));
    assertEquals(EventType.TRANSFER_SETTLED, event.eventType());
  }

  @Test
  void transferSettledRejectsNonPositiveLegs() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class,
        () -> new TransferSettled(a, b, ZERO, POSITIVE));
    assertThrows(IllegalArgumentException.class,
        () -> new TransferSettled(a, b, POSITIVE, ZERO));
  }

  @Test
  void transferFailedCarriesItsOwnType() {
    TransferFailed event = new TransferFailed("insufficient funds");
    assertEquals(EventType.TRANSFER_FAILED, event.eventType());
  }

  @Test
  void transferFailedRejectsBlankReason() {
    assertThrows(IllegalArgumentException.class, () -> new TransferFailed(" "));
  }
}
