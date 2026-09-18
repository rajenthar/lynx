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
import com.lynx.money.SupportedCurrencies;
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
   * One account per {@code (userId, currency)} — a
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
    // A NARROWER check than Money.currencyOf above — that only proves the
    // code is a REAL ISO-4217 currency (so "JPY" passes it), not that Lynx
    // actually supports it. SupportedCurrencies is the smaller business
    // whitelist fx-rate-service has real quotes for (see its own javadoc).
    if (!SupportedCurrencies.isSupported(currency)) {
      throw new ValidationException(
          "Unsupported currency: " + currency + " — supported currencies are "
              + SupportedCurrencies.CODES);
    }
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

  /** The currencies a new account (or a transfer) may be denominated in. */
  public List<String> listSupportedCurrencies() {
    return SupportedCurrencies.CODES;
  }

  /**
   * Called by auth-service right after a user completes OTP verification
   * (see {@code AccountServiceClient}/{@code InternalAccountController}'s
   * own javadoc) — every new user gets a starter SGD account so the
   * dashboard is never just an empty state. Deliberately idempotent and
   * silent on an existing account rather than throwing {@link
   * ConflictException}: this can legitimately be called more than once for
   * the same user (auth-service's own call is best-effort/retryable, and a
   * re-registration of a still-unverified email re-triggers verification),
   * and "the user already has an SGD account" is exactly the desired end
   * state either way, not an error.
   */
  public void seedDefaultAccount(String userId) {
    String defaultCurrency = SupportedCurrencies.CODES.get(0);
    if (accountRepository.findByUserIdAndCurrency(userId, defaultCurrency).isPresent()) {
      return;
    }
    try {
      accountRepository.save(new Account(UUID.randomUUID(), userId, defaultCurrency, Instant.now()));
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // Same race as createAccount above — another call already won.
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
      // retry with the SAME depositId (the write identity).
      throw new BusinessRuleException(ErrorCode.DOWNSTREAM_UNAVAILABLE, e.getMessage());
    } catch (LedgerRejectedException e) {
      throw new ValidationException(e.getMessage());
    }
    return depositId;
  }

  public Account getAccount(UUID accountId, String userId) {
    return getOwnedAccount(accountId, userId);
  }

  /**
   * Verifies the caller actually owns {@code accountId} BEFORE ever calling
   * ledger-service — see {@code LedgerServiceClient.history}'s javadoc for
   * why this ownership check can only live here, not in ledger-service
   * itself.
   */
  public List<LedgerServiceClient.HistoryEntry> getAccountHistory(UUID accountId, String userId, int limit) {
    getOwnedAccount(accountId, userId);
    return ledgerServiceClient.history(accountId, limit);
  }

  public List<Account> listAccounts(String userId) {
    return accountRepository.findByUserId(userId);
  }

  /**
   * The one lookup {@code transaction-service} needs to create a transfer
   * — both account ids, one round trip, merged deliberately (raised
   * directly) rather than two separate calls: a sender-account lookup and
   * a recipient-account lookup used to be two endpoints
   * ({@code list}'s on-behalf-of shape and a standalone
   * {@code /internal/accounts/resolve}), each guarded identically and each
   * only ever called by this one caller for this one purpose — merging
   * them removed dead-weight indirection without losing anything, since
   * neither had (or was expected to gain) an independent second caller.
   *
   * <p>Distinct, specific messages per side (sender vs recipient) so
   * {@code transaction-service} can surface the right 404 without needing
   * to inspect anything beyond the message text.
   */
  public ResolvedTransferAccounts resolveTransferAccounts(
      String senderUserId, String senderCurrencyCode, String recipientUserId, String recipientCurrencyCode) {
    String senderCurrency = Money.currencyOf(senderCurrencyCode).getCurrencyCode();
    UUID senderAccountId = accountRepository.findByUserIdAndCurrency(senderUserId, senderCurrency)
        .map(Account::getId)
        .orElseThrow(() -> new NotFoundException(
            "No " + senderCurrency + " account found for userId=" + senderUserId
                + " — create one before transferring"));

    String recipientCurrency = Money.currencyOf(recipientCurrencyCode).getCurrencyCode();
    UUID recipientAccountId = accountRepository.findByUserIdAndCurrency(recipientUserId, recipientCurrency)
        .map(Account::getId)
        .orElseThrow(() -> new NotFoundException(
            "Recipient has no " + recipientCurrency + " account: userId=" + recipientUserId));

    return new ResolvedTransferAccounts(senderAccountId, recipientAccountId);
  }

  public record ResolvedTransferAccounts(UUID senderAccountId, UUID recipientAccountId) {
  }

  private Account getOwnedAccount(UUID accountId, String userId) {
    Account account = accountRepository.findById(accountId)
        .orElseThrow(() -> new NotFoundException("Account not found: " + accountId));
    if (!account.getUserId().equals(userId)) {
      // 404, not 403 — same reasoning ledger-service's audit-trail scoping
      // uses: don't reveal an account exists
      // to a caller who doesn't own it.
      throw new NotFoundException("Account not found: " + accountId);
    }
    return account;
  }
}
