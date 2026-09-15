package com.lynx.ledger.api;

import com.lynx.ledger.dto.LedgerHistoryEntryView;
import com.lynx.ledger.service.LedgerService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The per-account audit trail (other-docs/12) — real ledger/fintech
 * infrastructure keeps this as an explicit, separate query from the fast
 * running balance rather than folding it in (TigerBeetle's {@code
 * get_account_transfers}; Modern Treasury's List Ledger Entries).
 *
 * <p>Deliberately NOT scoped by caller identity the way {@link
 * LedgerController}'s endpoints are — see {@code LedgerService#accountHistory}'s
 * javadoc for why {@code ledger-service} can't correctly enforce "does the
 * caller own this account" itself (it has no notion of account ownership;
 * that's {@code account-service}'s data). A caller-facing product would put
 * the ownership check in front of this — e.g. {@code account-service}
 * verifying the account is the caller's own before calling this endpoint
 * with its own internal-service identity — not built yet; tracked as a
 * known gap, same as every other explicitly-deferred piece in this project.
 */
@RestController
@RequestMapping("/v1/ledger/accounts/{accountId}")
public class LedgerAccountHistoryController {

  private static final Logger log = LoggerFactory.getLogger(LedgerAccountHistoryController.class);

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;

  private final LedgerService ledgerService;

  public LedgerAccountHistoryController(LedgerService ledgerService) {
    this.ledgerService = ledgerService;
  }

  @GetMapping("/history")
  public List<LedgerHistoryEntryView> history(
      @PathVariable UUID accountId,
      @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
    int boundedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    log.debug("History requested for account {}, limit={}", accountId, boundedLimit);
    return ledgerService.accountHistory(accountId, boundedLimit);
  }
}
