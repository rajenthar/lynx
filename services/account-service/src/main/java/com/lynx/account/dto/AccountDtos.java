package com.lynx.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AccountDtos {

  public record CreateAccountRequest(String currencyCode) {
  }

  public record DepositRequest(BigDecimal amount, String currencyCode) {
  }

  public record AccountView(
      UUID id, String userId, String currency,
      BigDecimal available, BigDecimal held, Instant updatedAt) {
  }

  /**
   * Acknowledges the ledger write succeeded — NOT the final balance.
   * {@code available}/{@code held} update asynchronously, once this
   * deposit's own {@code FundsDeposited} event comes back through the
   * Kafka projection — a caller that needs the updated
   * number polls {@code GET /v1/accounts/{id}} afterward.
   */
  public record DepositAcceptedView(UUID depositId, UUID accountId) {
  }

  /**
   * {@code transaction-service}'s one combined lookup — both account ids
   * a transfer needs, in a single call. A POST body, not GET query
   * params, deliberately: keeps both user ids out of the URL (and
   * therefore out of any access/proxy logs that capture request URLs but
   * not bodies) — the one internal lookup in this service shaped this
   * way, an intentional exception to the GET-for-reads convention every
   * other lookup here follows.
   */
  public record ResolveTransferRequest(
      String senderUserId, String senderCurrency, String recipientUserId, String recipientCurrency) {
  }

  public record ResolvedTransferAccountsView(UUID senderAccountId, UUID recipientAccountId) {
  }

  private AccountDtos() {
  }
}
