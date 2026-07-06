package com.lynx.common.error;

/** Request payload or parameter failed validation (HTTP 400). */
public final class ValidationException extends LynxException {

  public ValidationException(String message) {
    super(ErrorCode.VALIDATION_ERROR, message);
  }
}
