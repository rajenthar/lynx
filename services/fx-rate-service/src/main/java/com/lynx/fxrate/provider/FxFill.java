package com.lynx.fxrate.provider;

import java.math.BigDecimal;

/**
 * The outcome of one {@link FxProvider} execution attempt — exactly one of
 * {@link #filledRate} (success) or {@link #failureReason} (rejection) is
 * set, never both, never neither.
 */
public record FxFill(BigDecimal filledRate, String failureReason) {

  public FxFill {
    if ((filledRate == null) == (failureReason == null)) {
      throw new IllegalArgumentException(
          "Exactly one of filledRate/failureReason must be set, got filledRate="
              + filledRate + ", failureReason=" + failureReason);
    }
  }

  public static FxFill executed(BigDecimal filledRate) {
    return new FxFill(filledRate, null);
  }

  public static FxFill failed(String reason) {
    return new FxFill(null, reason);
  }

  public boolean succeeded() {
    return filledRate != null;
  }
}
