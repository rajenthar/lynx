package com.lynx.fxrate.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FxQuoteResponse(
    UUID quoteId, BigDecimal rate, String fromCurrency, String toCurrency, Instant expiresAt) {
}
