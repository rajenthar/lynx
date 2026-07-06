package com.lynx.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LynxExceptionTest {

  @Test
  void exceptionsCarryTheirErrorCode() {
    assertEquals(ErrorCode.NOT_FOUND, NotFoundException.of("Account", "acc-1").code());
    assertEquals(ErrorCode.VALIDATION_ERROR, new ValidationException("bad").code());
    assertEquals(ErrorCode.DUPLICATE_REQUEST, ConflictException.duplicateRequest("key-1").code());
    assertEquals(
        ErrorCode.INSUFFICIENT_FUNDS,
        BusinessRuleException.insufficientFunds("acc-1", "SGD").code());
  }

  @Test
  void errorCodesMapToHttpStatus() {
    assertEquals(404, ErrorCode.NOT_FOUND.httpStatus());
    assertEquals(409, ErrorCode.DUPLICATE_REQUEST.httpStatus());
    assertEquals(422, ErrorCode.INSUFFICIENT_FUNDS.httpStatus());
    assertEquals(500, ErrorCode.INTERNAL_ERROR.httpStatus());
  }

  @Test
  void apiErrorFromException() {
    ApiError error = ApiError.of(NotFoundException.of("Account", "acc-1"), "corr-123");
    assertEquals(ErrorCode.NOT_FOUND, error.code());
    assertEquals("corr-123", error.correlationId());
    assertTrue(error.message().contains("acc-1"));
    assertEquals(0, error.details().size());
  }
}
