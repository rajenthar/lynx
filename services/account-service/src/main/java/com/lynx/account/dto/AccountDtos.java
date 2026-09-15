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
   * Kafka projection (other-docs/12) — a caller that needs the updated
   * number polls {@code GET /v1/accounts/{id}} afterward.
   */
  public record DepositAcceptedView(UUID depositId, UUID accountId) {
  }

  private AccountDtos() {
  }
}
