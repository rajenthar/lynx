package com.lynx.fxrate.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynx.common.error.BusinessRuleException;
import com.lynx.money.SupportedCurrencies;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MockQuoteProviderTest {

  private static final class FixedClock extends Clock {
    private final Instant now = Instant.parse("2026-08-25T10:00:00Z");

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  @Test
  void quoteReturnsTheKnownRateAndExpiresAfterTheTtl() {
    Clock clock = new FixedClock();
    QuoteProvider provider = new MockQuoteProvider(clock);

    FxQuote quote = provider.quote(new BigDecimal("100.00"), "SGD", "USD");

    assertEquals(new BigDecimal("0.7412"), quote.rate());
    assertEquals("SGD", quote.fromCurrency());
    assertEquals("USD", quote.toCurrency());
    assertEquals(clock.instant().plus(MockQuoteProvider.QUOTE_TTL), quote.expiresAt());
  }

  @Test
  void reciprocalPairIsDerivedWhenOnlyTheDirectPairIsInTheTable() {
    QuoteProvider provider = new MockQuoteProvider(new FixedClock());

    FxQuote quote = provider.quote(new BigDecimal("100.00"), "USD", "SGD");

    assertTrue(quote.rate().compareTo(BigDecimal.ZERO) > 0);
  }

  @Test
  void sameCurrencyBothSidesReturnsRateOfOne() {
    QuoteProvider provider = new MockQuoteProvider(new FixedClock());

    FxQuote quote = provider.quote(new BigDecimal("100.00"), "SGD", "SGD");

    assertEquals(BigDecimal.ONE, quote.rate());
  }

  @Test
  void unknownPairThrowsCurrencyNotSupported() {
    QuoteProvider provider = new MockQuoteProvider(new FixedClock());

    assertThrows(BusinessRuleException.class,
        () -> provider.quote(new BigDecimal("100.00"), "SGD", "JPY"));
  }

  @Test
  void crossPairWithNeitherSideUsdIsBridgedThroughUsd() {
    // Neither "SGD/EUR" nor "EUR/SGD" is a direct RATES entry — both sides
    // are only quoted against USD, so this exercises the USD-bridging path
    // rather than a direct or reciprocal table lookup.
    QuoteProvider provider = new MockQuoteProvider(new FixedClock());

    FxQuote quote = provider.quote(new BigDecimal("100.00"), "SGD", "EUR");

    // SGD/USD=0.7412, USD/EUR=0.9217 -> SGD/EUR = 0.7412 * 0.9217
    BigDecimal expected = new BigDecimal("0.7412")
        .multiply(new BigDecimal("0.9217"), java.math.MathContext.DECIMAL64);
    assertEquals(0, expected.compareTo(quote.rate()));
  }

  /**
   * The actual guarantee this project needs: every currency account-service
   * will actually let a user open (see {@code SupportedCurrencies}) can be
   * transferred to/from every OTHER one — not just the ones with a direct
   * RATES entry. Exhaustively checks every ordered pair (including same
   * currency) rather than picking a few examples, since a gap here is
   * exactly the bug just fixed (SGD -> EUR silently had no route).
   */
  @Test
  void everySupportedCurrencyPairResolvesToAPositiveRate() {
    QuoteProvider provider = new MockQuoteProvider(new FixedClock());

    for (String from : SupportedCurrencies.CODES) {
      for (String to : SupportedCurrencies.CODES) {
        FxQuote quote = provider.quote(new BigDecimal("100.00"), from, to);
        assertTrue(quote.rate().compareTo(BigDecimal.ZERO) > 0,
            () -> from + " -> " + to + " did not resolve to a positive rate");
      }
    }
  }
}
