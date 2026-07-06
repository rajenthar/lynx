package com.lynx.common.error;

/**
 * Base exception for all Lynx business errors.
 *
 * <p>Sealed: the compiler knows every possible subtype, so exception handlers
 * (and switch patterns) can be checked for exhaustiveness. Infrastructure
 * failures (IO, SQL) are NOT LynxExceptions — they stay as their original
 * types and surface as INTERNAL_ERROR at the API boundary.
 */
public sealed class LynxException extends RuntimeException
    permits ValidationException, NotFoundException, ConflictException, BusinessRuleException {

  private final ErrorCode code;

  protected LynxException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  protected LynxException(ErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
