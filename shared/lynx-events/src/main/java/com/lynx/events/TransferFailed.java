package com.lynx.events;

/**
 * Saga compensation (ADR-001/003): the transfer failed at some phase and any
 * held/locked resources have been (or are being) released.
 */
public record TransferFailed(String reason) implements DomainEvent {

  public TransferFailed {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason is required");
    }
  }

  @Override
  public EventType eventType() {
    return EventType.TRANSFER_FAILED;
  }
}
