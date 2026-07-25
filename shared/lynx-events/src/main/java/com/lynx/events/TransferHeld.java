package com.lynx.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Saga phase 1 (ADR-001): funds reserved from {@code accountId}.
 *
 * <p>Consumed by account-service to apply {@code available -= amount, held += amount}
 * (ADR-005) — an idempotent update guarded by the envelope's {@code eventId}.
 */
public record TransferHeld(UUID accountId, MoneyAmount amount) implements DomainEvent {

  public TransferHeld {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(amount, "amount");
    if (amount.minorUnits() <= 0) {
      throw new IllegalArgumentException("amount must be positive, got " + amount.minorUnits());
    }
  }

  @Override
  public EventType eventType() {
    return EventType.TRANSFER_HELD;
  }
}
