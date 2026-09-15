package com.lynx.ledger.api;

import com.lynx.ledger.dto.LedgerPhaseResponse;
import com.lynx.ledger.dto.LedgerRequests.DepositRequest;
import com.lynx.ledger.service.LedgerService;
import com.lynx.money.Money;
import com.lynx.telemetry.MdcScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A direct, non-saga deposit (other-docs/12) — deliberately its own
 * controller/resource ({@code /v1/ledger/deposits/{depositId}}), not nested
 * under {@code LedgerController}'s {@code /v1/ledger/sagas/{sagaId}}: a
 * deposit isn't a saga phase and has no {@code sagaId}. {@code depositId}
 * plays the identical write-identity role {@code sagaId} plays for
 * {@link LedgerController} — see {@code LedgerService#deposit}. Same
 * ADR-007 trust boundary as every other write ({@link TrustedCaller}):
 * {@code account-service} calls this with a service-identity token +
 * {@code onBehalfOfUserId} on behalf of the real end user; an end user could
 * in principle call it directly with their own token instead, though today's
 * only real caller is {@code account-service}.
 */
@RestController
@RequestMapping("/v1/ledger/deposits/{depositId}")
public class LedgerDepositController {

  private static final Logger log = LoggerFactory.getLogger(LedgerDepositController.class);

  private final LedgerService ledgerService;

  public LedgerDepositController(LedgerService ledgerService) {
    this.ledgerService = ledgerService;
  }

  @PostMapping
  public LedgerPhaseResponse deposit(@PathVariable UUID depositId,
                                      @RequestBody DepositRequest request,
                                      HttpServletRequest httpRequest) {
    try (MdcScope ignored = MdcScope.forSaga(depositId.toString())) {
      String userId = TrustedCaller.userId(httpRequest, request.onBehalfOfUserId());
      log.info("DEPOSIT requested {} by userId={}, into account={}",
          depositId, userId, request.accountId());
      Money amount = Money.of(request.amount(), Money.currencyOf(request.currencyCode()));
      return ledgerService.deposit(depositId, userId, request.accountId(), amount);
    }
  }
}
