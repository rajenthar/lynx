package com.lynx.common.web;

import com.lynx.common.error.ApiError;
import com.lynx.common.error.LynxException;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates the sealed {@link LynxException} hierarchy into {@link ApiError}
 * responses, one HTTP status per {@code ErrorCode}.
 *
 * <p>Not auto-scanned: services opt in explicitly via
 * {@code @Import(GlobalExceptionHandler.class)} (see other-docs/01, Decision
 * on this class's deferred introduction) so a service that isn't a web
 * application never pulls in spring-webmvc.
 *
 * <p>Reads the correlation id straight out of SLF4J's MDC under the
 * {@code "correlationId"} key — the same key {@code lynx-security}'s
 * {@code CorrelationIdFilter} populates. lynx-common cannot depend on
 * lynx-security (wrong direction), so the key is duplicated here rather than
 * shared as a constant.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String CORRELATION_ID_MDC_KEY = "correlationId";
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(LynxException.class)
  public ResponseEntity<ApiError> handleLynxException(LynxException e) {
    String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
    int status = e.code().httpStatus();
    if (status >= 500) {
      // A "handled" business exception can still mean a real system-level
      // problem (SAGA_FAILED, DOWNSTREAM_UNAVAILABLE) — worth WARN, not
      // silently swallowed just because it's a known ErrorCode.
      log.warn("[correlationId={}] {} — {}", correlationId, e.code(), e.getMessage());
    } else {
      // Routine client-caused rejections (validation, conflict, not found) —
      // DEBUG is enough; they're expected traffic, not incidents.
      log.debug("[correlationId={}] {} — {}", correlationId, e.code(), e.getMessage());
    }
    return respond(ApiError.of(e, correlationId));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
    log.error("Unhandled exception [correlationId={}]", correlationId, e);
    return respond(ApiError.internal(correlationId));
  }

  private static ResponseEntity<ApiError> respond(ApiError body) {
    return ResponseEntity.status(HttpStatus.valueOf(body.code().httpStatus())).body(body);
  }
}
