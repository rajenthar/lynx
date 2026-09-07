package com.lynx.fxrate.provider;

import java.math.BigDecimal;

/**
 * The one seam where a real liquidity provider/market venue would plug in.
 * Everything else in this service — the idempotent-execution contract, the
 * durable {@code fx_executions} record, the API shape — is real and
 * permanent; only the implementation behind this interface is a
 * placeholder ({@link MockFxProvider}) until a real integration exists.
 *
 * <p>Deliberately synchronous: the uncertainty this service actually
 * guards against is the CALLER's own request possibly never seeing the
 * response (network drop, timeout) — not this method taking a long time
 * to decide. A real provider integration that IS genuinely async (e.g. a
 * webhook-based confirmation) would need a different shape than this one;
 * that's a real, separate design question for whenever a real provider is
 * chosen, not decided here.
 */
public interface FxProvider {

  FxFill execute(BigDecimal amount, String fromCurrency, String toCurrency, BigDecimal requestedRate);
}
