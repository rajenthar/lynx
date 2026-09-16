package com.lynx.account.api;

import com.lynx.account.dto.AccountDtos.ResolveTransferRequest;
import com.lynx.account.dto.AccountDtos.ResolvedTransferAccountsView;
import com.lynx.account.service.AccountService;
import com.lynx.account.service.AccountService.ResolvedTransferAccounts;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one lookup {@code transaction-service} needs to create a transfer —
 * both the sender's own account and the recipient's account, resolved by
 * {@code (userId, currency)}, in a single call. Merged deliberately from
 * two separate endpoints (a sender lookup via {@code AccountController.list}'s
 * on-behalf-of shape, a recipient lookup via a standalone
 * {@code /internal/accounts/resolve}) — each existed only to serve this
 * one caller for this one purpose, so splitting them bought no real
 * reusability, only an extra round trip and more code.
 *
 * <p>A {@code POST} with a body, not a {@code GET} with query params —
 * a deliberate exception to how every other lookup in this system is
 * shaped: keeps both user ids out of the request URL (and therefore out
 * of any access/proxy logs that capture URLs but not bodies), and reads
 * more cleanly than a four-parameter query string.
 *
 * <p>Every request here must be a proven {@code internal-service}-role
 * caller — enforced declaratively via {@link RequiresInternalService},
 * not an inline check: see that annotation's own javadoc for why this is
 * a plain Spring AOP aspect rather than Spring Security's
 * {@code @PreAuthorize}.
 *
 * <p>Deliberately narrow even so: returns only two opaque account ids,
 * nothing else about either user (no name, no balance) — a routing
 * lookup, not a directory service.
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

  private final AccountService accountService;

  public InternalAccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @RequiresInternalService
  @PostMapping("/resolve-transfer")
  public ResolvedTransferAccountsView resolveTransfer(@RequestBody ResolveTransferRequest request) {
    ResolvedTransferAccounts resolved = accountService.resolveTransferAccounts(
        request.senderUserId(), request.senderCurrency(),
        request.recipientUserId(), request.recipientCurrency());
    return new ResolvedTransferAccountsView(resolved.senderAccountId(), resolved.recipientAccountId());
  }
}
