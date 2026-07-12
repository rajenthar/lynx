package com.lynx.common.error;

/**
 * Authentication or authorization failure.
 *
 * <p>Two distinct cases, deliberately separated because the client's next action
 * differs:
 * <ul>
 *   <li>{@link ErrorCode#UNAUTHORIZED} (401) — "who are you?" The token is missing,
 *       expired, or invalid. Client should (re-)authenticate.
 *   <li>{@link ErrorCode#FORBIDDEN} (403) — "I know who you are, but you may not do
 *       this." The identity is valid but lacks the required role/permission.
 *       Re-authenticating will NOT help.
 * </ul>
 */
public final class AuthException extends LynxException {

  private AuthException(ErrorCode code, String message) {
    super(code, message);
  }

  /** 401 — token missing, expired, malformed, or signature invalid. */
  public static AuthException unauthorized(String message) {
    return new AuthException(ErrorCode.UNAUTHORIZED, message);
  }

  /** 403 — authenticated, but not permitted. */
  public static AuthException forbidden(String message) {
    return new AuthException(ErrorCode.FORBIDDEN, message);
  }
}
