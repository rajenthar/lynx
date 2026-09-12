package com.lynx.orchestrator.api;

import com.lynx.common.error.AuthException;
import com.lynx.orchestrator.config.JwtAuthFilter;
import com.lynx.orchestrator.domain.SagaState;
import com.lynx.orchestrator.dto.CreateSagaRequest;
import com.lynx.orchestrator.dto.SagaResponse;
import com.lynx.orchestrator.service.SagaOrchestratorService;
import com.lynx.security.UserContext;
import com.lynx.telemetry.MdcScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purely internal (other-docs/10 Decision 3) — unlike {@code
 * LedgerController}'s dual end-user-or-internal-service trust boundary,
 * EVERY caller here must already be a proven internal service; there is no
 * end-user path at all. {@code saga-orchestrator} is never called directly
 * by a client request — a future {@code transaction-service} is this
 * endpoint's only real caller, deriving {@code sagaId} from the actual
 * end-user's own {@code Idempotency-Key} (ADR-004's original chain) before
 * ever reaching here.
 */
@RestController
@RequestMapping("/internal/sagas")
public class SagaController {

  private static final Logger log = LoggerFactory.getLogger(SagaController.class);

  private static final String INTERNAL_SERVICE_ROLE = "internal-service";

  private final SagaOrchestratorService sagaOrchestratorService;

  public SagaController(SagaOrchestratorService sagaOrchestratorService) {
    this.sagaOrchestratorService = sagaOrchestratorService;
  }

  @PostMapping
  public SagaResponse create(@RequestBody CreateSagaRequest request, HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(request.sagaId().toString())) {
      requireInternalServiceCaller(httpRequest);
      String userId = request.onBehalfOfUserId();
      if (userId == null || userId.isBlank()) {
        throw AuthException.unauthorized("onBehalfOfUserId is required");
      }
      log.info("Saga {} requested for userId={}", request.sagaId(), userId);
      SagaState saga = sagaOrchestratorService.createSaga(
          request.sagaId(), userId, request.fromAccountId(), request.toAccountId(),
          request.amount(), request.fromCurrency(), request.toCurrency(),
          request.retriedFromSagaId());
      return SagaResponse.of(saga);
    }
  }

  /**
   * {@code onBehalfOfUserId} is REQUIRED here (unlike {@code
   * ledger-service}'s equivalent GET endpoints, where it's optional
   * because an ordinary end-user token can supply its own {@code userId}
   * instead) — this endpoint has no end-user path at all, so there is no
   * other source of {@code userId} to scope the lookup by.
   */
  @GetMapping("/{sagaId}")
  public SagaResponse get(@PathVariable UUID sagaId,
                           @RequestParam String onBehalfOfUserId,
                           HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
      requireInternalServiceCaller(httpRequest);
      if (onBehalfOfUserId == null || onBehalfOfUserId.isBlank()) {
        throw AuthException.unauthorized("onBehalfOfUserId is required");
      }
      return SagaResponse.of(sagaOrchestratorService.findOrThrow(sagaId, onBehalfOfUserId));
    }
  }

  /**
   * No {@code onBehalfOfUserId} trust-boundary split here — {@code
   * ledger-service}'s {@code LedgerController.userId(...)} has one because
   * it ALSO serves ordinary end-user tokens directly. This endpoint never
   * does, so a token failing this check is rejected outright, not fallen
   * back to {@code UserContext.userId()}.
   */
  private static void requireInternalServiceCaller(HttpServletRequest request) {
    UserContext userContext =
        (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    if (!userContext.hasRole(INTERNAL_SERVICE_ROLE)) {
      log.warn("Rejected non-internal-service caller {} for saga-orchestrator", userContext.userId());
      throw AuthException.forbidden("Only an internal-service caller may use saga-orchestrator");
    }
  }
}
