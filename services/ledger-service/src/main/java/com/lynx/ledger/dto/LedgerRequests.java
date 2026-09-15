package com.lynx.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request bodies for the four saga-phase endpoints.
 *
 * <p>{@code onBehalfOfUserId} is ADR-007 Option C's on-behalf-of field: only
 * present/honored when the caller authenticated as an internal service (a
 * token carrying the {@code internal-service} role, see
 * {@code TrustedCaller#userId}) — a normal end-user token must never send
 * it, and it's rejected if one does.
 */
public final class LedgerRequests {

  public record HoldRequest(
      UUID fromAccountId, BigDecimal amount, String currencyCode, String onBehalfOfUserId) {
  }

  public record LockRequest(
      BigDecimal lockedAmount, String currencyCode,
      String fromCurrency, String toCurrency, BigDecimal rate, Instant rateExpiresAt,
      String onBehalfOfUserId) {
  }

  public record SettleRequest(
      UUID fromAccountId, UUID toAccountId,
      BigDecimal debitedAmount, String debitedCurrency,
      BigDecimal creditedAmount, String creditedCurrency, String onBehalfOfUserId) {
  }

  public record ReleaseRequest(
      UUID accountId, BigDecimal amount, String currencyCode, String reason,
      String onBehalfOfUserId) {
  }

  /** other-docs/12 — {@code accountId} is the account being credited, not a saga counterparty. */
  public record DepositRequest(
      UUID accountId, BigDecimal amount, String currencyCode, String onBehalfOfUserId) {
  }

  private LedgerRequests() {
  }
}
