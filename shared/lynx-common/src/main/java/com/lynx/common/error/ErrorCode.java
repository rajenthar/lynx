package com.lynx.common.error;

/**
 * Canonical error codes returned by every Lynx service.
 *
 * <p>Codes are stable API contract values: clients switch on {@code code},
 * never on the human-readable message. Each code maps to exactly one HTTP
 * status so error handling is uniform across all services.
 */
public enum ErrorCode {

  // 4xx — client errors
  VALIDATION_ERROR(400),
  UNAUTHORIZED(401),
  FORBIDDEN(403),
  NOT_FOUND(404),
  CONFLICT(409),
  DUPLICATE_REQUEST(409),
  INSUFFICIENT_FUNDS(422),
  CURRENCY_NOT_SUPPORTED(422),
  LIMIT_EXCEEDED(429),
  RATE_LIMITED(429),

  // 5xx — server errors
  INTERNAL_ERROR(500),
  SAGA_FAILED(500),
  DOWNSTREAM_UNAVAILABLE(503);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
