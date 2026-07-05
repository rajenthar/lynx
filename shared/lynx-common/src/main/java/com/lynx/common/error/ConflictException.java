package com.lynx.common.error;

/**
 * Request conflicts with current state (HTTP 409).
 *
 * <p>Includes duplicate detection: a retry that hit the idempotency
 * UNIQUE constraint surfaces as {@link ErrorCode#DUPLICATE_REQUEST}
 * when the original response cannot be reconstructed.
 */
public final class ConflictException extends LynxException {

  public ConflictException(String message) {
    super(ErrorCode.CONFLICT, message);
  }

  public ConflictException(ErrorCode code, String message) {
    super(code, message);
  }

  public static ConflictException duplicateRequest(String idempotencyKey) {
    return new ConflictException(
        ErrorCode.DUPLICATE_REQUEST,
        "Duplicate request for idempotency key: " + idempotencyKey);
  }
}
