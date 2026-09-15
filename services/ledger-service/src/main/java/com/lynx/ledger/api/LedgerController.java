package com.lynx.ledger.api;

import com.lynx.ledger.config.JwtAuthFilter;
import com.lynx.ledger.dto.LedgerLegView;
import com.lynx.ledger.dto.LedgerPhaseResponse;
import com.lynx.ledger.dto.LedgerRequests.HoldRequest;
import com.lynx.ledger.dto.LedgerRequests.LockRequest;
import com.lynx.ledger.dto.LedgerRequests.ReleaseRequest;
import com.lynx.ledger.dto.LedgerRequests.SettleRequest;
import com.lynx.ledger.dto.LockedRateView;
import com.lynx.ledger.service.LedgerService;
import com.lynx.money.Money;
import com.lynx.security.UserContext;
import com.lynx.telemetry.MdcScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
 * ADR-001's four saga phases as synchronous commands, plus two read-only
 * endpoints (the audit trail and the locked rate). Every write is
 * idempotent per {@code (userId, sagaId, phase)} — no client-supplied
 * {@code Idempotency-Key} header (other-docs/08 Decision 29): ADR-003's
 * rate-lock expiry policy means each phase now happens at most once per
 * saga, forever, so that triple is a complete identity on its own. The two
 * {@code GET} endpoints are scoped by {@code userId} the same way every
 * write is — both to close the same saga_id-collision gap, and because a
 * caller has no business reading a saga's audit trail or locked rate if
 * it isn't theirs (other-docs/08 Decision 31). The caller identity used
 * comes from {@link JwtAuthFilter}'s verified {@link UserContext} for an
 * ordinary end-user token — but for a caller whose token proves it's an
 * internal service (ADR-007 Option C), it comes from an
 * {@code onBehalfOfUserId} value instead (request body for the four
 * {@code POST}s, a query param for the two {@code GET}s, since a
 * {@code GET} has no body), trusted only because the CALLER itself was
 * already verified. See {@link TrustedCaller#userId} for the exact trust
 * boundary — shared with {@link LedgerDepositController}.
 */
@RestController
@RequestMapping("/v1/ledger/sagas/{sagaId}")
public class LedgerController {

  private static final Logger log = LoggerFactory.getLogger(LedgerController.class);

  private final LedgerService ledgerService;

  public LedgerController(LedgerService ledgerService) {
    this.ledgerService = ledgerService;
  }

  @PostMapping("/hold")
  public LedgerPhaseResponse hold(@PathVariable UUID sagaId,
                                   @RequestBody HoldRequest request,
                                   HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
      String userId = TrustedCaller.userId(httpRequest, request.onBehalfOfUserId());
      log.info("HOLD requested for saga {} by userId={}", sagaId, userId);
      Money amount = Money.of(request.amount(), Money.currencyOf(request.currencyCode()));
      return ledgerService.hold(sagaId, userId, request.fromAccountId(), amount);
    }
  }

  @PostMapping("/lock")
  public LedgerPhaseResponse lock(@PathVariable UUID sagaId,
                                   @RequestBody LockRequest request,
                                   HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
      String userId = TrustedCaller.userId(httpRequest, request.onBehalfOfUserId());
      log.info("LOCK requested for saga {} by userId={}, {}->{} @ {}",
          sagaId, userId, request.fromCurrency(), request.toCurrency(), request.rate());
      Money lockedAmount = Money.of(request.lockedAmount(), Money.currencyOf(request.currencyCode()));
      return ledgerService.lock(sagaId, userId, lockedAmount, request.fromCurrency(),
          request.toCurrency(), request.rate(), request.rateExpiresAt());
    }
  }

  @PostMapping("/settle")
  public LedgerPhaseResponse settle(@PathVariable UUID sagaId,
                                     @RequestBody SettleRequest request,
                                     HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
      String userId = TrustedCaller.userId(httpRequest, request.onBehalfOfUserId());
      log.info("SETTLE requested for saga {} by userId={}", sagaId, userId);
      Money debited = Money.of(request.debitedAmount(), Money.currencyOf(request.debitedCurrency()));
      Money credited = Money.of(request.creditedAmount(), Money.currencyOf(request.creditedCurrency()));
      return ledgerService.settle(sagaId, userId, request.fromAccountId(), request.toAccountId(),
          debited, credited);
    }
  }

  @PostMapping("/release")
  public LedgerPhaseResponse release(@PathVariable UUID sagaId,
                                      @RequestBody ReleaseRequest request,
                                      HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
      String userId = TrustedCaller.userId(httpRequest, request.onBehalfOfUserId());
      log.info("RELEASE requested for saga {} by userId={}, reason={}",
          sagaId, userId, request.reason());
      Money amount = Money.of(request.amount(), Money.currencyOf(request.currencyCode()));
      return ledgerService.release(sagaId, userId, request.accountId(), amount, request.reason());
    }
  }

  @GetMapping
  public List<LedgerLegView> auditTrail(@PathVariable UUID sagaId,
                                         @RequestParam(required = false) String onBehalfOfUserId,
                                         HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
      String userId = TrustedCaller.userId(httpRequest, onBehalfOfUserId);
      log.debug("Audit trail requested for saga {} by userId={}", sagaId, userId);
      List<LedgerLegView> legs = ledgerService.auditTrail(sagaId, userId);
      log.debug("Audit trail for saga {} returned {} leg(s)", sagaId, legs.size());
      return legs;
    }
  }

  /**
   * The rate this saga locked in during LOCK — read back from the ledger
   * itself, for a caller (e.g. an orchestrator) that needs it on a later,
   * separate request to compute SETTLE's debited/credited amounts.
   */
  @GetMapping("/rate")
  public LockedRateView lockedRate(@PathVariable UUID sagaId,
                                    @RequestParam(required = false) String onBehalfOfUserId,
                                    HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
      String userId = TrustedCaller.userId(httpRequest, onBehalfOfUserId);
      log.debug("Locked-rate lookup requested for saga {} by userId={}", sagaId, userId);
      return ledgerService.lockedRate(sagaId, userId);
    }
  }

}
