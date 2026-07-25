package com.lynx.events;

/**
 * Discriminator for the saga lifecycle events (ADR-001 phases -> ADR-002 outbox).
 *
 * <p>Stored as the outbox table's {@code event_type} column and carried in the
 * envelope so a consumer can dispatch on it WITHOUT first deserializing the
 * whole payload into a concrete Java type (see {@link EventCodec#decode}).
 */
public enum EventType {
  TRANSFER_HELD,
  RATE_LOCKED,
  TRANSFER_SETTLED,
  TRANSFER_FAILED
}
