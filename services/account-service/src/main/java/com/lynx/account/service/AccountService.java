package com.lynx.account.service;

import com.lynx.account.client.LedgerServiceClient;
import com.lynx.account.client.LedgerServiceClient.LedgerRejectedException;
import com.lynx.account.client.LedgerServiceClient.LedgerUnavailableException;
import com.lynx.account.domain.Account;
import com.lynx.account.repository.AccountRepository;
import com.lynx.common.error.BusinessRuleException;
import com.lynx.common.error.ConflictException;
import com.lynx.common.error.ErrorCode;
import com.lynx.common.error.NotFoundException;
import com.lynx.common.error.ValidationException;
import com.lynx.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AccountService {

  private final AccountRepository accountRepository;
  private final LedgerServiceClient ledgerServiceClient;

  public AccountService(AccountRepository accountRepository, LedgerServiceClient ledgerServiceClient) {
    this.accountRepository = accountRepository;
    this.ledgerServiceClient = ledgerServiceClient;
  }

  /**
   * One account per {@code (userId, currency)} (other-docs/12) — a
   * deliberate decision, not a default: a user may hold as many DIFFERENT
   * currencies as they like, just not two accounts in the SAME one.
   * Enforced here AND by the database's own {@code UNIQUE(user_id,
   * currency)} constraint — this check is the friendly 409 for the
   * common case; the constraint is the real guarantee under a race
   * (two concurrent create-account calls for the same currency).
   */
  public Account createAccount(String userId, String currencyCode) {
    if (currencyCode == null || currencyCode.isBlank()) {
      throw new ValidationException("currencyCode is required");
    }
    // Validates the currency is real/known — Money.currencyOf (backed by
    // java.util.Currency.getInstance) already requires an exact uppercase
    // ISO-4217 code and throws on anything else, so no separate case
    // normalization is needed here.
    String currency = Money.currencyOf(currencyCode).getCurrencyCode();
    if (accountRepository.findByUserIdAndCurrency(userId, currency).isPresent()) {
      throw new ConflictException(
          "An account in " + currency + " already exists for this user");
    }
    Account account = new Account(UUID.randomUUID(), userId, currency, Instant.now());
    try {
      return accountRepository.save(account);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // The race the pre-check above can't fully close: two concurrent
      // createAccount calls for the SAME (userId, currency) can both pass
      // the check before either commits. The database's own UNIQUE
      // constraint is the real guarantee — this just gives it the same
      // friendly 409 shape as the common (non-racing) case above.
      throw new ConflictException(
          "An account in " + currency + " already exists for this user");
    }
  }

  /**
   * @return the {@code depositId} this deposit was written under — the
   *     balance itself updates asynchronously (see {@code DepositAcceptedView}).
   */
  public UUID deposit(UUID accountId, String userId, Money amount) {
    Account account = getOwnedAccount(accountId, userId);
    if (!account.getCurrency().equals(amount.currencyCode())) {
      throw new ValidationException(
          "Account is denominated in " + account.getCurrency() + ", not " + amount.currencyCode());
    }
    UUID depositId = UUID.randomUUID();
    try {
      ledgerServiceClient.deposit(depositId, userId, accountId, amount);
    } catch (LedgerUnavailableException e) {
      // Transient — same ErrorCode/reasoning ledger-service's own
      // serialization-conflict handling uses: a 503 a caller can safely
      // retry with the SAME depositId (the write identity, other-docs/12).
      throw new BusinessRuleException(ErrorCode.DOWNSTREAM_UNAVAILABLE, e.getMessage());
    } catch (LedgerRejectedException e) {
      throw new ValidationException(e.getMessage());
    }
    return depositId;
  }

  public Account getAccount(UUID accountId, String userId) {
    return getOwnedAccount(accountId, userId);
  }

  public List<Account> listAccounts(String userId) {
    return accountRepository.findByUserId(userId);
  }

  private Account getOwnedAccount(UUID accountId, String userId) {
    Account account = accountRepository.findById(accountId)
        .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
    if (!account.getUserId().equals(userId)) {
      // 404, not 403 — same reasoning ledger-service's audit-trail scoping
      // uses (other-docs/08 Decision 31): don't reveal an account exists
      // to a caller who doesn't own it.
      throw new NotFoundException("Account not found: " + accountId);
    }
    return account;
  }
}
