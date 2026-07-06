package com.lynx.money;

/**
 * Thrown when a Money operation is used incorrectly.
 *
 * <p>These represent PROGRAMMER errors, not business conditions:
 * <ul>
 *   <li>adding two different currencies (SGD + EUR) without converting
 *   <li>constructing money with more precision than the currency allows
 *       (e.g. 100.005 USD)
 * </ul>
 *
 * <p>This is intentionally NOT part of lynx-common's LynxException hierarchy.
 * A currency mismatch is a bug in our code (a 500), not a client-actionable
 * 4xx error. Business conditions like "insufficient funds" are decided by the
 * services that use Money, using Money's comparison methods — never thrown by
 * Money itself.
 */
public final class MoneyException extends RuntimeException {

  public MoneyException(String message) {
    super(message);
  }
}
