package com.lynx.ledger.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** The FX rate a saga locked in during its LOCK phase, read back from the ledger. */
public record LockedRateView(
    BigDecimal rate, String fromCurrency, String toCurrency, Instant expiresAt) {
}
