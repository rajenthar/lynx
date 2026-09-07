package com.lynx.fxrate.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code executionId} IS the idempotency key for this request — client-
 * generated (typically deterministic from {@code sagaId}, same idiom as
 * ledger-service's own Idempotency-Key discipline), reused unchanged on
 * every retry of the same logical attempt.
 */
public record FxExecutionRequest(
    UUID executionId, UUID sagaId, BigDecimal amount,
    String fromCurrency, String toCurrency, BigDecimal requestedRate) {
}
