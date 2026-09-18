package com.lynx.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.account.client.LedgerServiceClient;
import com.lynx.account.client.LedgerServiceClient.LedgerRejectedException;
import com.lynx.account.client.LedgerServiceClient.LedgerUnavailableException;
import com.lynx.account.domain.Account;
import com.lynx.account.repository.AccountRepository;
import com.lynx.common.error.BusinessRuleException;
import com.lynx.common.error.ErrorCode;
import com.lynx.common.error.NotFoundException;
import com.lynx.common.error.ValidationException;
import com.lynx.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

  /**
   * A hand-rolled test double, not a Mockito mock — {@code LedgerServiceClient}
   * is a concrete class, and this JDK's Mockito inline mock maker can't
   * instrument concrete classes (Byte Buddy/JDK 25, same issue
   * auth-service's JwtIssuerTest and ledger-service's TransactionTemplate
   * mocking hit). {@code null} constructor args are safe here — the real
   * constructor only stores them, never dereferences them.
   */
  private static final class RecordingLedgerServiceClient extends LedgerServiceClient {
    final List<Object[]> calls = new ArrayList<>();
    Consumer<Object[]> onDeposit = call -> { };

    RecordingLedgerServiceClient() {
      super(null, null, null);
    }

    @Override
    public void deposit(UUID depositId, String userId, UUID accountId, Money amount) {
      Object[] call = {depositId, userId, accountId, amount};
      calls.add(call);
      onDeposit.accept(call);
    }
  }

  private AccountRepository accountRepository;
  private RecordingLedgerServiceClient ledgerServiceClient;
  private AccountService accountService;

  @BeforeEach
  void setUp() {
    accountRepository = mock(AccountRepository.class);
    ledgerServiceClient = new RecordingLedgerServiceClient();
    accountService = new AccountService(accountRepository, ledgerServiceClient);
    when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void createAccountRejectsAnUnknownCurrency() {
    assertThatThrownBy(() -> accountService.createAccount("user-1", "NOT_A_CURRENCY"))
        .isInstanceOf(Exception.class);
  }

  @Test
  void createAccountRejectsARealButUnsupportedCurrency() {
    // JPY is a genuine ISO-4217 code (Money.currencyOf accepts it) but not
    // one of the currencies Lynx actually supports (SupportedCurrencies) —
    // this is the narrower business check, not the ISO-validity check
    // covered by createAccountRejectsAnUnknownCurrency above.
    assertThatThrownBy(() -> accountService.createAccount("user-1", "JPY"))
        .isInstanceOf(com.lynx.common.error.ValidationException.class);
  }

  @Test
  void createAccountSavesAZeroBalanceAccount() {
    Account account = accountService.createAccount("user-1", "SGD");

    assertThat(account.getUserId()).isEqualTo("user-1");
    assertThat(account.getCurrency()).isEqualTo("SGD");
    assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getHeld()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void createAccountRejectsALowercaseCurrencyCode() {
    // Money.currencyOf (java.util.Currency.getInstance) requires an exact
    // uppercase ISO-4217 code — this isn't a case-normalization feature,
    // just documenting the existing validation's actual behavior.
    assertThatThrownBy(() -> accountService.createAccount("user-1", "sgd"))
        .isInstanceOf(Exception.class);
  }

  @Test
  void createAccountRejectsASecondAccountInTheSameCurrency() {
    Account existing = new Account(UUID.randomUUID(), "user-1", "SGD", Instant.now());
    when(accountRepository.findByUserIdAndCurrency("user-1", "SGD")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> accountService.createAccount("user-1", "SGD"))
        .isInstanceOf(com.lynx.common.error.ConflictException.class);
  }

  @Test
  void createAccountAllowsDifferentCurrenciesForTheSameUser() {
    when(accountRepository.findByUserIdAndCurrency("user-1", "SGD")).thenReturn(Optional.empty());
    when(accountRepository.findByUserIdAndCurrency("user-1", "USD")).thenReturn(Optional.empty());

    Account sgd = accountService.createAccount("user-1", "SGD");
    Account usd = accountService.createAccount("user-1", "USD");

    assertThat(sgd.getCurrency()).isEqualTo("SGD");
    assertThat(usd.getCurrency()).isEqualTo("USD");
  }

  @Test
  void createAccountTranslatesADatabaseRaceIntoTheSameConflictShape() {
    // Simulates two concurrent creates for the same (userId, currency):
    // both pass the pre-check (empty), but the DB's own UNIQUE constraint
    // catches the second one at save() time.
    when(accountRepository.save(any())).thenThrow(
        new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(() -> accountService.createAccount("user-1", "SGD"))
        .isInstanceOf(com.lynx.common.error.ConflictException.class);
  }

  @Test
  void getAccountThrowsNotFoundRatherThanForbiddenForAnUnownedAccount() {
    UUID accountId = UUID.randomUUID();
    Account someoneElses = new Account(accountId, "user-2", "SGD", Instant.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(someoneElses));

    assertThatThrownBy(() -> accountService.getAccount(accountId, "user-1"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void getAccountThrowsNotFoundForATrulyMissingAccount() {
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> accountService.getAccount(accountId, "user-1"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void depositRejectsACurrencyMismatch() {
    UUID accountId = UUID.randomUUID();
    Account account = new Account(accountId, "user-1", "SGD", Instant.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    Money usd = Money.of(new BigDecimal("10.00"), Money.currencyOf("USD"));

    assertThatThrownBy(() -> accountService.deposit(accountId, "user-1", usd))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void depositCallsLedgerServiceWithAFreshDepositId() {
    UUID accountId = UUID.randomUUID();
    Account account = new Account(accountId, "user-1", "SGD", Instant.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));

    UUID depositId = accountService.deposit(accountId, "user-1", amount);

    assertThat(ledgerServiceClient.calls).hasSize(1);
    assertThat(ledgerServiceClient.calls.get(0)).containsExactly(depositId, "user-1", accountId, amount);
  }

  @Test
  void depositTranslatesLedgerUnavailableIntoARetryable503() {
    UUID accountId = UUID.randomUUID();
    Account account = new Account(accountId, "user-1", "SGD", Instant.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));
    ledgerServiceClient.onDeposit = call -> {
      throw new LedgerUnavailableException("down", new RuntimeException());
    };

    assertThatThrownBy(() -> accountService.deposit(accountId, "user-1", amount))
        .isInstanceOf(BusinessRuleException.class)
        .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE));
  }

  @Test
  void depositTranslatesLedgerRejectedIntoAValidationError() {
    UUID accountId = UUID.randomUUID();
    Account account = new Account(accountId, "user-1", "SGD", Instant.now());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    Money amount = Money.of(new BigDecimal("100.00"), Money.currencyOf("SGD"));
    ledgerServiceClient.onDeposit = call -> {
      throw new LedgerRejectedException("rejected");
    };

    assertThatThrownBy(() -> accountService.deposit(accountId, "user-1", amount))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void resolveTransferAccountsReturnsBothIdsInOneCall() {
    UUID senderAccountId = UUID.randomUUID();
    UUID recipientAccountId = UUID.randomUUID();
    when(accountRepository.findByUserIdAndCurrency("user-1", "SGD"))
        .thenReturn(Optional.of(new Account(senderAccountId, "user-1", "SGD", Instant.now())));
    when(accountRepository.findByUserIdAndCurrency("recipient-1", "USD"))
        .thenReturn(Optional.of(new Account(recipientAccountId, "recipient-1", "USD", Instant.now())));

    AccountService.ResolvedTransferAccounts resolved =
        accountService.resolveTransferAccounts("user-1", "SGD", "recipient-1", "USD");

    assertThat(resolved.senderAccountId()).isEqualTo(senderAccountId);
    assertThat(resolved.recipientAccountId()).isEqualTo(recipientAccountId);
  }

  @Test
  void resolveTransferAccountsIs404WhenTheSenderHasNoAccountInThatCurrency() {
    when(accountRepository.findByUserIdAndCurrency("user-1", "SGD")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> accountService.resolveTransferAccounts("user-1", "SGD", "recipient-1", "USD"))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("userId=user-1");
  }

  @Test
  void resolveTransferAccountsIs404WhenTheRecipientHasNoAccountInThatCurrency() {
    when(accountRepository.findByUserIdAndCurrency("user-1", "SGD"))
        .thenReturn(Optional.of(new Account(UUID.randomUUID(), "user-1", "SGD", Instant.now())));
    when(accountRepository.findByUserIdAndCurrency("recipient-1", "USD")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> accountService.resolveTransferAccounts("user-1", "SGD", "recipient-1", "USD"))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("Recipient");
  }

  @Test
  void listSupportedCurrenciesMatchesTheWhitelist() {
    assertThat(accountService.listSupportedCurrencies())
        .containsExactly("SGD", "USD", "EUR", "GBP");
  }

  @Test
  void seedDefaultAccountCreatesAnSgdAccountWhenTheUserHasNone() {
    when(accountRepository.findByUserIdAndCurrency("user-1", "SGD")).thenReturn(Optional.empty());

    accountService.seedDefaultAccount("user-1");

    var captor = org.mockito.ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
    assertThat(captor.getValue().getCurrency()).isEqualTo("SGD");
  }

  @Test
  void seedDefaultAccountIsANoOpWhenTheUserAlreadyHasAnSgdAccount() {
    Account existing = new Account(UUID.randomUUID(), "user-1", "SGD", Instant.now());
    when(accountRepository.findByUserIdAndCurrency("user-1", "SGD")).thenReturn(Optional.of(existing));

    accountService.seedDefaultAccount("user-1");

    verify(accountRepository, org.mockito.Mockito.never()).save(any());
  }
}
