package com.lynx.events;

import com.lynx.money.Money;

/**
 * Wire representation of a {@link Money} value: a lossless integer minor-units
 * amount plus an ISO-4217 currency code.
 *
 * <p>Events never carry {@code Money} (BigDecimal) directly over the wire (ADR-005):
 * an integer number of minor units is exact and unambiguous across
 * services/languages, where a decimal string invites parsing/scale mismatches.
 * Convert at the boundary: {@link #of(Money)} when publishing, {@link #toMoney()}
 * when consuming.
 */
public record MoneyAmount(long minorUnits, String currencyCode) {

  public MoneyAmount {
    if (currencyCode == null || currencyCode.isBlank()) {
      throw new IllegalArgumentException("currencyCode is required");
    }
  }

  public static MoneyAmount of(Money money) {
    return new MoneyAmount(money.toMinorUnits(), money.currencyCode());
  }

  public Money toMoney() {
    return Money.ofMinor(minorUnits, Money.currencyOf(currencyCode));
  }
}
