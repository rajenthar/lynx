package com.lynx.events;

import java.util.Objects;
import java.util.UUID;

/**
 * Saga compensation (ADR-001/003): the transfer failed at some phase and
 * {@code amount} has been released back to {@code accountId}.
 *
 * <p>{@code accountId}/{@code amount} were added (other-docs/12) once
 * account-service's projection needed them — without them, there was no way
 * to know which account to credit back, or how much, on a compensation.
 * Mirrors {@code ledger-service}'s {@code RELEASE_CR} leg, which is always
 * exactly the account and amount this event now carries.
 */
public record TransferFailed(UUID accountId, MoneyAmount amount, String reason) implements DomainEvent {

  public TransferFailed {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(amount, "amount");
    if (amount.minorUnits() <= 0) {
      throw new IllegalArgumentException("amount must be positive, got " + amount.minorUnits());
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason is required");
    }
  }

  @Override
  public EventType eventType() {
    return EventType.TRANSFER_FAILED;
  }
}
