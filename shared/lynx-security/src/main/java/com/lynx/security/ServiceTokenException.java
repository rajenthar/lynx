package com.lynx.security;

/**
 * Signals that {@link ServiceTokenProvider} could not obtain a service
 * token — the token endpoint was unreachable, returned an error, or
 * returned a malformed response.
 *
 * <p>Deliberately NOT part of {@code lynx-common}'s sealed
 * {@code LynxException} hierarchy — same reasoning as
 * {@code DuplicateRequestException} (lynx-idempotency): this is an
 * internal infrastructure failure of a CLIENT trying to reach a
 * dependency, not a client-facing business error with its own
 * {@code ErrorCode}. Left uncaught, it falls through to
 * {@code GlobalExceptionHandler}'s catch-all → 500, which is the
 * correct default; a caller that wants a more specific outcome (e.g.
 * treating it as 503 DOWNSTREAM_UNAVAILABLE) can catch and translate it
 * explicitly.
 */
public final class ServiceTokenException extends RuntimeException {

  public ServiceTokenException(String message) {
    super(message);
  }

  public ServiceTokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
