package com.lynx.common.error;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response body returned by every Lynx service.
 *
 * <p>Example:
 * <pre>{@code
 * {
 *   "code": "INSUFFICIENT_FUNDS",
 *   "message": "Insufficient SGD funds in account acc-123",
 *   "correlationId": "b3e1...",
 *   "timestamp": "2026-07-04T10:30:00Z",
 *   "details": []
 * }
 * }</pre>
 *
 * <p>{@code correlationId} lets a client report an error that support can
 * trace across every service hop (see ADR-004: Correlation-ID is for
 * tracing, never idempotency).
 */
public record ApiError(
    ErrorCode code,
    String message,
    String correlationId,
    Instant timestamp,
    List<String> details
) {

  public ApiError {
    details = details == null ? List.of() : List.copyOf(details);
  }

  public static ApiError of(LynxException e, String correlationId) {
    return new ApiError(e.code(), e.getMessage(), correlationId, Instant.now(), List.of());
  }

  public static ApiError internal(String correlationId) {
    return new ApiError(
        ErrorCode.INTERNAL_ERROR,
        "An unexpected error occurred",
        correlationId,
        Instant.now(),
        List.of());
  }
}
