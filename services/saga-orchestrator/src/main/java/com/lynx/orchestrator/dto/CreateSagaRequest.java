package com.lynx.orchestrator.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The one internal, service-to-service request shape this service accepts
 * (other-docs/10 Decision 3) — {@code sagaId} and {@code userId} are
 * supplied by the caller (a future {@code transaction-service}, which
 * derived {@code sagaId} from the real end-user's own {@code
 * Idempotency-Key}, per ADR-004's original chain), not generated here.
 * {@code retriedFromSagaId} is optional, audit-only (other-docs/10 Decision
 * 2) — null for a brand-new transfer.
 */
public record CreateSagaRequest(
    UUID sagaId,
    UUID fromAccountId,
    UUID toAccountId,
    BigDecimal amount,
    String fromCurrency,
    String toCurrency,
    UUID retriedFromSagaId,
    String onBehalfOfUserId) {
}
