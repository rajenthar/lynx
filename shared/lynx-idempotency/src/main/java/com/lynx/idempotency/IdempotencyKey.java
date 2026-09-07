package com.lynx.idempotency;

import java.util.UUID;

import com.lynx.common.error.ValidationException;

/**
 * A validated Idempotency Key — the value that identifies one attempt at an
 * idempotent operation (ADR-004).
 *
 * <p>Sourced differently depending on the caller: a real client-supplied
 * {@code Idempotency-Key} HTTP header for a service that has one (e.g. a
 * future {@code transaction-service} saga-creation endpoint), or an
 * internally-derived value for a service that doesn't need a separate
 * client key at all — {@code ledger-service} derives it from its own
 * {@code sagaId}, {@code fx-rate-service} from its own {@code executionId}
 * (see other-docs/08 Decision 29). Either way, construction enforces the
 * contract (present + UUID format), so any {@code IdempotencyKey} instance
 * in the system is known-good — the same "invalid values cannot exist"
 * discipline as {@code Money} and {@code PageRequest}. A missing or
 * malformed value fails here, as a 400, before any business logic runs.
 *
 * <p>Two named factories instead of one generic {@code of(...)}, so which
 * kind of value is in hand is visible right at the call site, not just in
 * this javadoc: {@link #fromClientHeader} for a real end-user-supplied
 * {@code Idempotency-Key} header, {@link #derivedFrom} for a value this
 * service computed itself and no client ever sent. Both produce the exact
 * same validated {@code IdempotencyKey} — the distinction is documentation
 * at the point of construction, not a different runtime behavior.
 */
public record IdempotencyKey(String value) {

  public IdempotencyKey {
    if (value == null || value.isBlank()) {
      throw new ValidationException("Idempotency key is required");
    }
    try {
      UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Idempotency key must be a UUID, got: " + value);
    }
  }

  /**
   * Wraps a real, client-supplied {@code Idempotency-Key} header value —
   * e.g. a future {@code transaction-service} saga-creation endpoint,
   * reading the header a caller actually sent.
   */
  public static IdempotencyKey fromClientHeader(String value) {
    return new IdempotencyKey(value);
  }

  /**
   * Wraps a value this service computed itself — never sent by any client
   * as an {@code Idempotency-Key} header. E.g. {@code ledger-service}
   * deriving one from its own {@code sagaId}, or {@code fx-rate-service}
   * from its own {@code executionId} (other-docs/08 Decision 29).
   */
  public static IdempotencyKey derivedFrom(String value) {
    return new IdempotencyKey(value);
  }
}
