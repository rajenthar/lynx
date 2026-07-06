package com.lynx.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, currency-aware monetary amount backed by {@link BigDecimal}.
 *
 * <p>Design goal: make money bugs structurally impossible.
 * <ul>
 *   <li><b>No floating point.</b> The amount is a BigDecimal; {@code 0.1 + 0.2}
 *       is exactly {@code 0.3}, never {@code 0.30000000000000004}.
 *   <li><b>No mixing currencies.</b> {@code add}/{@code subtract}/comparisons on
 *       different currencies throw — you cannot accidentally sum SGD and EUR.
 *   <li><b>No silent rounding.</b> Construction is strict: an amount with more
 *       precision than the currency allows (e.g. {@code 100.005} USD) is
 *       rejected. Rounding only happens where you explicitly ask for it
 *       ({@code multiply}, {@code convert}) with a chosen {@link RoundingMode}.
 *   <li><b>No lost pennies.</b> {@link #allocate(int)} splits an amount so the
 *       parts sum back to exactly the original.
 * </ul>
 *
 * <p>The stored amount is always normalized to the currency's fraction digits
 * (USD → 2, JPY → 0, BHD → 3), so {@code equals} behaves as expected:
 * {@code Money.of("100", USD).equals(Money.of("100.00", USD))} is {@code true}.
 *
 * <p><b>Default rounding is HALF_EVEN</b> ("banker's rounding") — the accounting
 * standard, because it has no systematic bias: {@code 2.5→2}, {@code 3.5→4},
 * so rounding errors cancel out over many operations instead of always drifting up.
 */
public record Money(BigDecimal amount, Currency currency) {

  /** Banker's rounding — the financial default. */
  public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

  /**
   * Canonical constructor: validates and normalizes to the currency's scale.
   *
   * @throws MoneyException if the currency has no defined minor unit, or the
   *     amount carries more precision than the currency allows
   */
  public Money {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");

    int scale = currency.getDefaultFractionDigits();
    if (scale < 0) {
      // e.g. XXX "no currency"; a real amount must have a defined minor unit
      throw new MoneyException("Currency has no minor unit: " + currency.getCurrencyCode());
    }

    // Strict: reject amounts more precise than the currency (likely a bug).
    // Trailing zeros are insignificant, so 100.10 USD is fine but 100.005 is not.
    if (amount.stripTrailingZeros().scale() > scale) {
      throw new MoneyException(
          "Amount " + amount.toPlainString() + " has more precision than "
              + currency.getCurrencyCode() + " allows (" + scale + " dp). "
              + "Use Money.ofRounded(...) to round explicitly.");
    }

    // Normalize to exact currency scale (pads zeros; never rounds — validated above).
    amount = amount.setScale(scale, RoundingMode.UNNECESSARY);
  }

  // ---------------------------------------------------------------------------
  // Factories
  // ---------------------------------------------------------------------------

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  /** Convenience: {@code Money.of("100.00", "SGD")}. */
  public static Money of(String amount, String currencyCode) {
    return new Money(new BigDecimal(amount), currencyOf(currencyCode));
  }

  /** Zero in the given currency (e.g. an opening balance). */
  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  public static Money zero(String currencyCode) {
    return zero(currencyOf(currencyCode));
  }

  /**
   * Build from integer minor units (e.g. cents): {@code ofMinor(10050, USD)}
   * → {@code 100.50 USD}. This is the exact, lossless way to carry money over
   * the wire or in a database as a whole number.
   */
  public static Money ofMinor(long minorUnits, Currency currency) {
    int scale = currency.getDefaultFractionDigits();
    return new Money(BigDecimal.valueOf(minorUnits, scale), currency);
  }

  /**
   * Round an over-precise amount to the currency scale, explicitly.
   * Use this at FX/interest boundaries where rounding is expected and intended.
   */
  public static Money ofRounded(BigDecimal amount, Currency currency, RoundingMode mode) {
    int scale = currency.getDefaultFractionDigits();
    if (scale < 0) {
      throw new MoneyException("Currency has no minor unit: " + currency.getCurrencyCode());
    }
    return new Money(amount.setScale(scale, mode), currency);
  }

  /** Resolve an ISO-4217 code, turning an unknown code into a MoneyException. */
  public static Currency currencyOf(String code) {
    try {
      return Currency.getInstance(code);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new MoneyException("Unknown currency code: " + code);
    }
  }

  // ---------------------------------------------------------------------------
  // Arithmetic (same currency enforced)
  // ---------------------------------------------------------------------------

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  public Money negate() {
    return new Money(amount.negate(), currency);
  }

  public Money abs() {
    return new Money(amount.abs(), currency);
  }

  /**
   * Multiply by a scalar (e.g. a fee rate of 0.015), rounding the result to
   * the currency scale using {@link #DEFAULT_ROUNDING}. Currency is unchanged.
   */
  public Money multiply(BigDecimal factor) {
    return multiply(factor, DEFAULT_ROUNDING);
  }

  public Money multiply(BigDecimal factor, RoundingMode mode) {
    BigDecimal raw = amount.multiply(factor);
    return new Money(raw.setScale(currency.getDefaultFractionDigits(), mode), currency);
  }

  /**
   * Convert to another currency at the given exchange rate, rounding the
   * result to the TARGET currency's scale.
   *
   * <p>{@code Money.of("100.00","SGD").convert(EUR, rate("0.68"))} → {@code 68.00 EUR}.
   * Rounding happens exactly once, here, at the conversion boundary.
   */
  public Money convert(Currency target, BigDecimal rate, RoundingMode mode) {
    BigDecimal raw = amount.multiply(rate);
    return new Money(raw.setScale(target.getDefaultFractionDigits(), mode), target);
  }

  public Money convert(Currency target, BigDecimal rate) {
    return convert(target, rate, DEFAULT_ROUNDING);
  }

  // ---------------------------------------------------------------------------
  // Allocation — split without losing pennies
  // ---------------------------------------------------------------------------

  /**
   * Split into {@code n} parts as evenly as possible. Any indivisible remainder
   * (leftover minor units) is spread one-per-part across the first parts, so the
   * parts always sum back to exactly this amount.
   *
   * <p>{@code Money.of("100.00","USD").allocate(3)} → {@code [33.34, 33.33, 33.33]}.
   */
  public List<Money> allocate(int n) {
    if (n <= 0) {
      throw new MoneyException("Cannot allocate into " + n + " parts");
    }
    long total = toMinorUnits();
    long base = total / n;
    long remainder = total - base * n; // pennies to distribute (respects sign via long division)

    List<Money> parts = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      long share = base + (i < Math.abs(remainder) ? Long.signum(remainder) : 0);
      parts.add(ofMinor(share, currency));
    }
    return parts;
  }

  /**
   * Split by integer ratios. Leftover minor units go one-at-a-time to the
   * earliest ratios, so the parts sum back to exactly this amount.
   *
   * <p>{@code Money.of("100.00","USD").allocate(1, 1, 2)} → {@code [25.00, 25.00, 50.00]}.
   */
  public List<Money> allocate(int... ratios) {
    if (ratios.length == 0) {
      throw new MoneyException("Cannot allocate with no ratios");
    }
    long ratioTotal = 0;
    for (int r : ratios) {
      if (r < 0) {
        throw new MoneyException("Allocation ratios must be non-negative, got " + r);
      }
      ratioTotal += r;
    }
    if (ratioTotal == 0) {
      throw new MoneyException("Allocation ratios sum to zero");
    }

    long total = toMinorUnits();
    long allocated = 0;
    List<Money> parts = new ArrayList<>(ratios.length);
    for (int r : ratios) {
      long share = total * r / ratioTotal; // truncates toward zero
      parts.add(ofMinor(share, currency));
      allocated += share;
    }
    // Distribute the truncation remainder one minor unit at a time.
    long remainder = total - allocated;
    for (int i = 0; remainder != 0 && i < parts.size(); i++) {
      long bump = Long.signum(remainder);
      parts.set(i, parts.get(i).add(ofMinor(bump, currency)));
      remainder -= bump;
    }
    return parts;
  }

  // ---------------------------------------------------------------------------
  // Queries and comparisons
  // ---------------------------------------------------------------------------

  public boolean isZero() {
    return amount.signum() == 0;
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isGreaterThan(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) > 0;
  }

  public boolean isGreaterThanOrEqual(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) >= 0;
  }

  public boolean isLessThan(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) < 0;
  }

  /** Integer minor units, e.g. {@code 100.50 USD} → {@code 10050}. */
  public long toMinorUnits() {
    return amount.movePointRight(currency.getDefaultFractionDigits()).longValueExact();
  }

  public String currencyCode() {
    return currency.getCurrencyCode();
  }

  @Override
  public String toString() {
    return amount.toPlainString() + " " + currency.getCurrencyCode();
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new MoneyException(
          "Currency mismatch: " + currency.getCurrencyCode()
              + " vs " + other.currency.getCurrencyCode()
              + " (convert first)");
    }
  }
}
