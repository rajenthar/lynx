package com.lynx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynx.money.Money;

import org.junit.jupiter.api.Test;

class MoneyAmountTest {

  @Test
  void roundTripsThroughMoney() {
    Money original = Money.of("100.50", "USD");
    MoneyAmount wire = MoneyAmount.of(original);

    assertEquals(10050, wire.minorUnits());
    assertEquals("USD", wire.currencyCode());
    assertEquals(original, wire.toMoney());
  }

  @Test
  void jpyHasNoDecimalMinorUnits() {
    Money original = Money.of("500", "JPY");
    MoneyAmount wire = MoneyAmount.of(original);

    assertEquals(500, wire.minorUnits());
    assertEquals(original, wire.toMoney());
  }

  @Test
  void rejectsBlankCurrencyCode() {
    assertThrows(IllegalArgumentException.class, () -> new MoneyAmount(100, " "));
  }
}
