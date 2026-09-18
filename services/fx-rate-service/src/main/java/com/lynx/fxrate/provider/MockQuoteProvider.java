package com.lynx.fxrate.provider;

import com.lynx.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic, in-process stand-in for a real market-data/quoting feed —
 * a fixed rate table, no real volatility. Same "the interface is the real
 * design decision, this implementation is a placeholder" reasoning as
 * {@link MockFxProvider}.
 */
@Component
public class MockQuoteProvider implements QuoteProvider {

  private static final Logger log = LoggerFactory.getLogger(MockQuoteProvider.class);

  /** Real quotes are only valid briefly — real market rates move fast. */
  static final Duration QUOTE_TTL = Duration.ofSeconds(60);

  /**
   * Every pair here has USD on one side — this is a hub-and-spoke table,
   * not a full pairwise matrix. A pair with neither side being USD (e.g.
   * SGD/EUR, both real Lynx-supported currencies) has no direct entry and
   * is never expected to — {@link #rateFor} bridges it through USD
   * instead of needing an entry for every possible combination.
   */
  private static final String USD = "USD";

  private static final Map<String, BigDecimal> RATES = Map.of(
      "SGD/USD", new BigDecimal("0.7412"),
      "EUR/USD", new BigDecimal("1.0850"),
      "GBP/USD", new BigDecimal("1.2650"),
      "USD/SGD", new BigDecimal("1.3492"),
      "USD/EUR", new BigDecimal("0.9217"),
      "USD/GBP", new BigDecimal("0.7905")
  );

  private final Clock clock;

  public MockQuoteProvider() {
    this(Clock.systemUTC());
  }

  MockQuoteProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public FxQuote quote(BigDecimal amount, String fromCurrency, String toCurrency) {
    BigDecimal rate = rateFor(fromCurrency, toCurrency);
    FxQuote quote = new FxQuote(UUID.randomUUID(), rate, fromCurrency, toCurrency,
        clock.instant().plus(QUOTE_TTL));
    // DEBUG, not INFO: quotes are cheap and high-volume by design (Decision
    // 6) — every quote issued at INFO would drown out genuinely notable
    // events in production log volume.
    log.debug("Issued quote {}: {} -> {} @ {}, expires {}",
        quote.quoteId(), fromCurrency, toCurrency, rate, quote.expiresAt());
    return quote;
  }

  private static BigDecimal rateFor(String fromCurrency, String toCurrency) {
    if (fromCurrency.equals(toCurrency)) {
      return BigDecimal.ONE;
    }
    Optional<BigDecimal> direct = legRate(fromCurrency, toCurrency);
    if (direct.isPresent()) {
      return direct.get();
    }
    // Neither side of this pair is directly quoted against the other —
    // true for any pair where NEITHER side is USD (e.g. SGD/EUR), since
    // RATES only ever quotes each currency against USD. Bridge it:
    // fromCurrency -> USD -> toCurrency, using the same single-leg
    // lookup (direct or reciprocal) for each half.
    Optional<BigDecimal> fromToUsd = legRate(fromCurrency, USD);
    Optional<BigDecimal> usdToTarget = legRate(USD, toCurrency);
    if (fromToUsd.isPresent() && usdToTarget.isPresent()) {
      return fromToUsd.get().multiply(usdToTarget.get(), MathContext.DECIMAL64);
    }
    log.warn("No mock rate available for {} -> {}", fromCurrency, toCurrency);
    throw BusinessRuleException.currencyNotSupported(fromCurrency + " -> " + toCurrency);
  }

  /** A single leg's rate, direct or derived from its reciprocal — empty if this currency isn't quoted at all. */
  private static Optional<BigDecimal> legRate(String fromCurrency, String toCurrency) {
    if (fromCurrency.equals(toCurrency)) {
      return Optional.of(BigDecimal.ONE);
    }
    BigDecimal direct = RATES.get(fromCurrency + "/" + toCurrency);
    if (direct != null) {
      return Optional.of(direct);
    }
    BigDecimal reciprocal = RATES.get(toCurrency + "/" + fromCurrency);
    if (reciprocal != null) {
      return Optional.of(BigDecimal.ONE.divide(reciprocal, MathContext.DECIMAL64));
    }
    return Optional.empty();
  }
}
