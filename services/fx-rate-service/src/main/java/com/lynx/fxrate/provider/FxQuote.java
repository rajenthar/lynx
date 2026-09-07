package com.lynx.fxrate.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An ephemeral, advisory rate — NOT durably persisted (see
 * {@link QuoteProvider}'s javadoc for why). {@code quoteId} exists purely
 * for correlation/tracing across a quote → execute pair; it's a plain
 * generated UUID, not a database key.
 */
public record FxQuote(
    UUID quoteId, BigDecimal rate, String fromCurrency, String toCurrency, Instant expiresAt) {
}
