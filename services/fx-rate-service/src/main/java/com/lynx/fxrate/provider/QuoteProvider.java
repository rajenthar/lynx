package com.lynx.fxrate.provider;

import java.math.BigDecimal;

/**
 * The quoting half of this service (see other-docs/09 Decision 6) —
 * deliberately kept in the SAME service as {@link FxProvider}'s execution
 * half, not split into a separate quoting engine: at this project's
 * current scale (one mock provider, no real market-data feed, no
 * independent scaling need), a dedicated service would be premature
 * complexity, not a correctness requirement. Real platforms usually DO
 * split these — quote volume vastly exceeds execute volume, and a live
 * market-data feed has a very different latency/availability profile than
 * a trade-execution venue — revisit this colocation choice if either of
 * those ever becomes true here.
 *
 * <p>A quote is intentionally NOT durably stored: it's cheap, advisory,
 * and non-committal (a caller can request many and act on none) — nothing
 * about it needs {@code IdempotencyGuard}-style deduplication, unlike
 * {@link FxProvider#execute}, which IS a real commitment.
 */
public interface QuoteProvider {

  FxQuote quote(BigDecimal amount, String fromCurrency, String toCurrency);
}
