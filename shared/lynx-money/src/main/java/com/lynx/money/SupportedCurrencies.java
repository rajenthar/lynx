package com.lynx.money;

import java.util.List;
import java.util.Set;

/**
 * The business-level whitelist of currencies Lynx actually supports —
 * distinct from {@link Money#currencyOf}, which only validates that a code
 * is a REAL ISO-4217 currency (so "JPY" or "INR" pass that check even
 * though nothing in this system prices them). This is the smaller,
 * deliberate set fx-rate-service actually has quotes for (see its
 * {@code MockQuoteProvider}'s own rate table) — account creation and
 * transfers are both validated against THIS list, not against every
 * ISO-4217 code that happens to exist.
 */
public final class SupportedCurrencies {

  /** Ordered for a stable, predictable dropdown — not alphabetical, base currency first. */
  public static final List<String> CODES = List.of("SGD", "USD", "EUR", "GBP");

  private static final Set<String> CODE_SET = Set.copyOf(CODES);

  public static boolean isSupported(String code) {
    return code != null && CODE_SET.contains(code);
  }

  private SupportedCurrencies() {
  }
}
