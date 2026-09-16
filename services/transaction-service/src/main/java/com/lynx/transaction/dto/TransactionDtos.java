package com.lynx.transaction.dto;

import java.math.BigDecimal;
import java.util.UUID;

public final class TransactionDtos {

  public record CreateTransferRequest(
      String recipientUserId, String fromCurrency, String toCurrency, BigDecimal amount) {
  }

  /**
   * {@code 202 Accepted} — the saga was CREATED, not necessarily finished.
   * {@code saga-orchestrator} advances it asynchronously via its own
   * polling scheduler; the client polls {@code GET /v1/transfers/{sagaId}}
   * for completion.
   */
  public record TransferAcceptedView(UUID sagaId, String status) {
  }

  public record TransferStatusView(UUID sagaId, String status, String failureReason) {
  }

  private TransactionDtos() {
  }
}
