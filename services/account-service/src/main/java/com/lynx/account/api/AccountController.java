package com.lynx.account.api;

import com.lynx.account.config.JwtAuthFilter;
import com.lynx.account.domain.Account;
import com.lynx.account.dto.AccountDtos.AccountView;
import com.lynx.account.dto.AccountDtos.CreateAccountRequest;
import com.lynx.account.dto.AccountDtos.DepositAcceptedView;
import com.lynx.account.dto.AccountDtos.DepositRequest;
import com.lynx.account.service.AccountService;
import com.lynx.money.Money;
import com.lynx.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ordinary end-user auth only (no ADR-007 on-behalf-of shape here) — this
 * service has no internal-service caller today; every request is a real,
 * live end user's own JWT, scoped to their own accounts.
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

  private final AccountService accountService;

  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping
  public AccountView create(@RequestBody CreateAccountRequest request, HttpServletRequest httpRequest) {
    String userId = userId(httpRequest);
    Account account = accountService.createAccount(userId, request.currencyCode());
    return toView(account);
  }

  @PostMapping("/{accountId}/deposit")
  public DepositAcceptedView deposit(@PathVariable UUID accountId,
                                      @RequestBody DepositRequest request,
                                      HttpServletRequest httpRequest) {
    String userId = userId(httpRequest);
    Money amount = Money.of(request.amount(), Money.currencyOf(request.currencyCode()));
    UUID depositId = accountService.deposit(accountId, userId, amount);
    return new DepositAcceptedView(depositId, accountId);
  }

  @GetMapping("/{accountId}")
  public AccountView get(@PathVariable UUID accountId, HttpServletRequest httpRequest) {
    String userId = userId(httpRequest);
    return toView(accountService.getAccount(accountId, userId));
  }

  @GetMapping
  public List<AccountView> list(HttpServletRequest httpRequest) {
    String userId = userId(httpRequest);
    return accountService.listAccounts(userId).stream().map(AccountController::toView).toList();
  }

  private static String userId(HttpServletRequest request) {
    UserContext userContext =
        (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    return userContext.userId();
  }

  private static AccountView toView(Account account) {
    return new AccountView(account.getId(), account.getUserId(), account.getCurrency(),
        account.getAvailable(), account.getHeld(), account.getUpdatedAt());
  }
}
