package com.lynx.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Saga phase 3 (ADR-001): funds moved from the source's held balance to the
 * recipient's available balance, in the recipient's currency.
 *
 * <p>{@code debited} and {@code credited} are deliberately separate amounts
 * (different currencies either side of the FX conversion) rather than one
 * shared {@link MoneyAmount} — mirrors the ledger's SETTLE_DR/SETTLE_CR legs.
 */
public record TransferSettled(
    UUID sourceAccountId,
    UUID recipientAccountId,
    MoneyAmount debited,
    MoneyAmount credited
) implements DomainEvent {

  public TransferSettled {
    Objects.requireNonNull(sourceAccountId, "sourceAccountId");
    Objects.requireNonNull(recipientAccountId, "recipientAccountId");
    Objects.requireNonNull(debited, "debited");
    Objects.requireNonNull(credited, "credited");
    if (debited.minorUnits() <= 0) {
      throw new IllegalArgumentException("debited must be positive, got " + debited.minorUnits());
    }
    if (credited.minorUnits() <= 0) {
      throw new IllegalArgumentException("credited must be positive, got " + credited.minorUnits());
    }
  }

  @Override
  public EventType eventType() {
    return EventType.TRANSFER_SETTLED;
  }
}
