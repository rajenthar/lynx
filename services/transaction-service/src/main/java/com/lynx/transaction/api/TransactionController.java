package com.lynx.transaction.api;

import com.lynx.idempotency.IdempotencyHeaders;
import com.lynx.security.UserContext;
import com.lynx.telemetry.MdcScope;
import com.lynx.transaction.config.JwtAuthFilter;
import com.lynx.transaction.dto.TransactionDtos.CreateTransferRequest;
import com.lynx.transaction.dto.TransactionDtos.TransferAcceptedView;
import com.lynx.transaction.dto.TransactionDtos.TransferStatusView;
import com.lynx.transaction.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one real client-facing entry point for a transfer —
 * end-user JWT only (confirmed), no ADR-007 on-behalf-of path inbound.
 * Every OUTBOUND call this service makes (to {@code saga-orchestrator}/
 * {@code account-service}) goes out under this service's OWN
 * service-identity token, asserting {@code onBehalfOfUserId} — never a
 * forwarded end-user token, same trust model every other internal caller
 * in this system already uses. Derives {@code sagaId} deterministically
 * from {@code (userId, Idempotency-Key)} — the real chain ADR-004
 * originally specified.
 */
@RestController
@RequestMapping("/v1/transfers")
public class TransactionController {

  private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

  private final TransactionService transactionService;

  public TransactionController(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @PostMapping
  public ResponseEntity<TransferAcceptedView> create(
      @RequestBody CreateTransferRequest request,
      @RequestHeader(IdempotencyHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      HttpServletRequest httpRequest) {
    String userId = userId(httpRequest);
    try (MdcScope ignored = MdcScope.forSaga(idempotencyKey)) {
      log.info("POST /v1/transfers for userId={}, Idempotency-Key={}", userId, idempotencyKey);
      TransferAcceptedView view = transactionService.createTransfer(userId, idempotencyKey, request);
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }
  }

  @GetMapping("/{sagaId}")
  public TransferStatusView get(@PathVariable UUID sagaId, HttpServletRequest httpRequest) {
    String userId = userId(httpRequest);
    return transactionService.getTransfer(sagaId, userId);
  }

  private static String userId(HttpServletRequest request) {
    UserContext userContext =
        (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    return userContext.userId();
  }
}
