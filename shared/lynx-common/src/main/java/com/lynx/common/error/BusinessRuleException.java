package com.lynx.common.error;

/**
 * A domain rule rejected the operation (HTTP 4xx, code-specific).
 *
 * <p>Used for money-domain rules: insufficient funds, unsupported
 * currency, transfer limits. The specific {@link ErrorCode} carries
 * the exact reason.
 */
public final class BusinessRuleException extends LynxException {

  public BusinessRuleException(ErrorCode code, String message) {
    super(code, message);
  }

  public static BusinessRuleException insufficientFunds(String accountId, String currency) {
    return new BusinessRuleException(
        ErrorCode.INSUFFICIENT_FUNDS,
        "Insufficient " + currency + " funds in account " + accountId);
  }

  public static BusinessRuleException currencyNotSupported(String currency) {
    return new BusinessRuleException(
        ErrorCode.CURRENCY_NOT_SUPPORTED,
        "Currency not supported: " + currency);
  }
}
