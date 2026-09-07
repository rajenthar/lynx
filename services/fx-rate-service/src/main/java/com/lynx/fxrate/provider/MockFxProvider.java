package com.lynx.fxrate.provider;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Deterministic, in-process stand-in for a real liquidity provider —
 * always fills at EXACTLY the requested rate, never fails.
 *
 * <p>This is a deliberate choice, not a shortcut: per the design discussion
 * behind this service (see other-docs/09), the locked rate is a customer-
 * facing PROMISE with its own validity window, not a raw pass-through of
 * whatever the wholesale market does — the platform is expected to absorb
 * its own execution slippage as a cost, not pass it to the customer. A
 * real provider integration would still be expected to fill at (or very
 * near) the requested rate in the normal case; this mock simply always
 * takes that normal case, since simulating real market failure modes
 * belongs to whichever real provider is eventually chosen, not to this
 * placeholder.
 *
 * <p>Tests needing a FAILED outcome substitute their own {@link FxProvider}
 * (a plain Mockito stub) rather than this class growing failure-injection
 * knobs it has no real basis for.
 */
@Component
public class MockFxProvider implements FxProvider {

  @Override
  public FxFill execute(BigDecimal amount, String fromCurrency, String toCurrency,
                         BigDecimal requestedRate) {
    return FxFill.executed(requestedRate);
  }
}
