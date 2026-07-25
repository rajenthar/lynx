package com.lynx.events;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Saga phase 2 (ADR-001): the FX rate for this transfer has been locked in.
 *
 * <p>{@code rate} is a plain {@link BigDecimal}, not a {@link MoneyAmount} — an
 * exchange rate is a dimensionless ratio (e.g. {@code 0.6853} SGD-per-EUR), not
 * an amount of any one currency, so {@code Money}'s per-currency decimal-place
 * rules don't apply to it.
 */
public record RateLocked(String fromCurrency, String toCurrency, BigDecimal rate)
    implements DomainEvent {

  public RateLocked {
    Objects.requireNonNull(fromCurrency, "fromCurrency");
    Objects.requireNonNull(toCurrency, "toCurrency");
    Objects.requireNonNull(rate, "rate");
    if (rate.signum() <= 0) {
      throw new IllegalArgumentException("rate must be positive, got " + rate);
    }
  }

  @Override
  public EventType eventType() {
    return EventType.RATE_LOCKED;
  }
}
