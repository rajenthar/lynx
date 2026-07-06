package com.lynx.common.error;

/** Requested resource does not exist (HTTP 404). */
public final class NotFoundException extends LynxException {

  public NotFoundException(String message) {
    super(ErrorCode.NOT_FOUND, message);
  }

  public static NotFoundException of(String resource, Object id) {
    return new NotFoundException(resource + " not found: " + id);
  }
}
