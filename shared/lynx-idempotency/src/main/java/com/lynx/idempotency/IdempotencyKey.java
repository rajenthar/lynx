package com.lynx.idempotency;

import java.util.UUID;

import com.lynx.common.error.ValidationException;

/**
 * A validated, client-provided Idempotency-Key.
 *
 * <p>Construction enforces the contract (present + UUID format), so any
 * {@code IdempotencyKey} instance in the system is known-good — the same
 * "invalid values cannot exist" discipline as {@code Money} and
 * {@code PageRequest}. A missing or malformed header fails here, as a 400,
 * before any business logic runs.
 */
public record IdempotencyKey(String value) {

  public IdempotencyKey {
    if (value == null || value.isBlank()) {
      throw new ValidationException("Idempotency-Key header is required");
    }
    try {
      UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Idempotency-Key must be a UUID, got: " + value);
    }
  }

  public static IdempotencyKey of(String value) {
    return new IdempotencyKey(value);
  }
}
