package com.lynx.events;

/**
 * Sealed root of the saga lifecycle events.
 *
 * <p>Sealed the same way as {@code LynxException}: the compiler knows every
 * permitted subtype, so {@link EventCodec} and any consumer-side {@code switch}
 * over event types can be checked for exhaustiveness — adding a fifth event
 * later forces a conscious update everywhere one is handled.
 */
public sealed interface DomainEvent
    permits TransferHeld, RateLocked, TransferSettled, TransferFailed {

  EventType eventType();
}
