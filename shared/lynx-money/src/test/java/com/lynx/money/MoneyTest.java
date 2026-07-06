package com.lynx.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Nested
  class ConstructionAndEquality {

    @Test
    void normalizesToCurrencyScale() {
      assertEquals("100.00 SGD", Money.of("100", "SGD").toString());
      assertEquals("100.00 SGD", Money.of("100.0", "SGD").toString());
    }

    @Test
    void equalsIgnoresInsignificantScale() {
      assertEquals(Money.of("100", "USD"), Money.of("100.00", "USD"));
      assertEquals(Money.of("100", "USD").hashCode(), Money.of("100.00", "USD").hashCode());
    }

    @Test
    void differentCurrenciesAreNotEqual() {
      assertFalse(Money.of("100.00", "USD").equals(Money.of("100.00", "SGD")));
    }

    @Test
    void rejectsOverPreciseAmount() {
      // 100.005 has more precision than USD's 2 dp — likely a bug, so it throws
      MoneyException e =
          assertThrows(MoneyException.class, () -> Money.of("100.005", "USD"));
      assertTrue(e.getMessage().contains("precision"));
    }

    @Test
    void acceptsInsignificantTrailingZeros() {
      // 100.100 strips to 100.1, within USD precision → allowed
      assertEquals("100.10 USD", Money.of("100.100", "USD").toString());
    }

    @Test
    void unknownCurrencyRejected() {
      assertThrows(MoneyException.class, () -> Money.of("100", "ZZZ"));
    }
  }

  @Nested
  class CurrencyScaleVariations {

    @Test
    void jpyHasNoMinorUnit() {
      // JPY: 0 fraction digits
      assertEquals("100 JPY", Money.of("100", "JPY").toString());
      assertThrows(MoneyException.class, () -> Money.of("100.5", "JPY"));
    }

    @Test
    void bhdHasThreeDecimals() {
      // BHD: 3 fraction digits
      assertEquals("100.500 BHD", Money.of("100.5", "BHD").toString());
      assertEquals(100500, Money.of("100.5", "BHD").toMinorUnits());
    }
  }

  @Nested
  class NoFloatingPoint {

    @Test
    void classicFloatBugDoesNotHappen() {
      // 0.1 + 0.2 == 0.30000000000000004 in double; exact here
      Money sum = Money.of("0.10", "USD").add(Money.of("0.20", "USD"));
      assertEquals(Money.of("0.30", "USD"), sum);
    }
  }

  @Nested
  class Arithmetic {

    @Test
    void addAndSubtract() {
      assertEquals(Money.of("150.00", "SGD"),
          Money.of("100.00", "SGD").add(Money.of("50.00", "SGD")));
      assertEquals(Money.of("50.00", "SGD"),
          Money.of("100.00", "SGD").subtract(Money.of("50.00", "SGD")));
    }

    @Test
    void addingDifferentCurrenciesThrows() {
      MoneyException e = assertThrows(MoneyException.class,
          () -> Money.of("100.00", "USD").add(Money.of("100.00", "EUR")));
      assertTrue(e.getMessage().contains("mismatch"));
    }

    @Test
    void negateAndAbs() {
      assertEquals(Money.of("-100.00", "USD"), Money.of("100.00", "USD").negate());
      assertEquals(Money.of("100.00", "USD"), Money.of("-100.00", "USD").abs());
    }

    @Test
    void multiplyRoundsToCurrencyScale() {
      // 100.00 * 0.015 = 1.5 -> 1.50
      assertEquals(Money.of("1.50", "USD"),
          Money.of("100.00", "USD").multiply(new BigDecimal("0.015")));
    }

    @Test
    void multiplyUsesBankersRounding() {
      // 1.005 * 1 rounds HALF_EVEN at 2dp -> 1.00 (round to even), not 1.01
      Money result = Money.of("1.00", "USD").multiply(new BigDecimal("1.005"));
      assertEquals(Money.of("1.00", "USD"), result);
    }
  }

  @Nested
  class Conversion {

    @Test
    void convertsToTargetCurrencyScale() {
      // 100.00 SGD * 0.68 = 68.00 EUR
      Money eur = Money.of("100.00", "SGD")
          .convert(Money.currencyOf("EUR"), new BigDecimal("0.68"));
      assertEquals(Money.of("68.00", "EUR"), eur);
      assertEquals("EUR", eur.currencyCode());
    }

    @Test
    void convertRoundsAtBoundaryOnce() {
      // 100.00 * 0.6853 = 68.53 EUR (rounded to 2dp)
      Money eur = Money.of("100.00", "SGD")
          .convert(Money.currencyOf("EUR"), new BigDecimal("0.6853"), RoundingMode.HALF_EVEN);
      assertEquals(Money.of("68.53", "EUR"), eur);
    }
  }

  @Nested
  class MinorUnits {

    @Test
    void roundTripThroughMinorUnits() {
      Money m = Money.ofMinor(10050, Money.currencyOf("USD"));
      assertEquals(Money.of("100.50", "USD"), m);
      assertEquals(10050, m.toMinorUnits());
    }

    @Test
    void jpyMinorUnitsAreWhole() {
      assertEquals(500, Money.of("500", "JPY").toMinorUnits());
    }
  }

  @Nested
  class Allocation {

    @Test
    void equalSplitDistributesRemainderPennies() {
      List<Money> parts = Money.of("100.00", "USD").allocate(3);
      assertEquals(
          List.of(Money.of("33.34", "USD"), Money.of("33.33", "USD"), Money.of("33.33", "USD")),
          parts);
    }

    @Test
    void equalSplitSumsBackToOriginal() {
      Money original = Money.of("100.00", "USD");
      Money sum = original.allocate(7).stream().reduce(Money.zero("USD"), Money::add);
      assertEquals(original, sum);
    }

    @Test
    void ratioSplit() {
      List<Money> parts = Money.of("100.00", "USD").allocate(1, 1, 2);
      assertEquals(
          List.of(Money.of("25.00", "USD"), Money.of("25.00", "USD"), Money.of("50.00", "USD")),
          parts);
    }

    @Test
    void ratioSplitSumsBackWithAwkwardNumbers() {
      Money original = Money.of("100.00", "USD");
      Money sum = original.allocate(1, 1, 1).stream().reduce(Money.zero("USD"), Money::add);
      assertEquals(original, sum); // 33.34 + 33.33 + 33.33
    }

    @Test
    void negativeAmountAllocationSumsBack() {
      Money original = Money.of("-100.00", "USD");
      Money sum = original.allocate(3).stream().reduce(Money.zero("USD"), Money::add);
      assertEquals(original, sum);
    }

    @Test
    void allocateZeroPartsThrows() {
      assertThrows(MoneyException.class, () -> Money.of("100.00", "USD").allocate(0));
    }
  }

  @Nested
  class Comparisons {

    @Test
    void signQueries() {
      assertTrue(Money.of("1.00", "USD").isPositive());
      assertTrue(Money.of("-1.00", "USD").isNegative());
      assertTrue(Money.zero("USD").isZero());
    }

    @Test
    void orderingSameCurrency() {
      Money hundred = Money.of("100.00", "USD");
      Money fifty = Money.of("50.00", "USD");
      assertTrue(hundred.isGreaterThan(fifty));
      assertTrue(fifty.isLessThan(hundred));
      assertTrue(hundred.isGreaterThanOrEqual(Money.of("100.00", "USD")));
    }

    @Test
    void comparingDifferentCurrenciesThrows() {
      assertThrows(MoneyException.class,
          () -> Money.of("100.00", "USD").isGreaterThan(Money.of("50.00", "EUR")));
    }
  }
}
