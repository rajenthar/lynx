package com.lynx.fxrate.provider;

import com.lynx.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
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

  /** Real quotes are only valid briefly — real market rates move fast; see other-docs/09 Decision 6. */
  static final Duration QUOTE_TTL = Duration.ofSeconds(60);

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
    String pair = fromCurrency + "/" + toCurrency;
    BigDecimal direct = RATES.get(pair);
    if (direct != null) {
      return direct;
    }
    BigDecimal reciprocal = RATES.get(toCurrency + "/" + fromCurrency);
    if (reciprocal != null) {
      return BigDecimal.ONE.divide(reciprocal, MathContext.DECIMAL64);
    }
    log.warn("No mock rate available for {} -> {}", fromCurrency, toCurrency);
    throw BusinessRuleException.currencyNotSupported(fromCurrency + " -> " + toCurrency);
  }
}
