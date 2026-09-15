package com.lynx.events;

import java.util.Objects;
import java.util.UUID;

/**
 * A non-saga money-movement event (other-docs/12): funds added to
 * {@code accountId} from outside the system (the {@code FUNDING_SOURCE}
 * system account). Consumed by account-service to apply
 * {@code available += amount} — the deposit counterpart to
 * {@link TransferHeld}/{@link TransferSettled}/{@link TransferFailed},
 * which are all saga-driven.
 */
public record FundsDeposited(UUID accountId, MoneyAmount amount) implements DomainEvent {

  public FundsDeposited {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(amount, "amount");
    if (amount.minorUnits() <= 0) {
      throw new IllegalArgumentException("amount must be positive, got " + amount.minorUnits());
    }
  }

  @Override
  public EventType eventType() {
    return EventType.FUNDS_DEPOSITED;
  }
}
