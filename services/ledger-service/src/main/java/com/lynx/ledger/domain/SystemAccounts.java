package com.lynx.ledger.domain;

import java.util.UUID;

/**
 * Fixed, well-known account ids used as the counterparty leg of HOLD/LOCK
 * entries — not real user accounts, but ledger-internal clearing accounts.
 */
public final class SystemAccounts {

  /** Counterparty for HOLD_DR / RELEASE_CR: funds held pending settlement. */
  public static final UUID HOLD_POOL =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  /** Counterparty for LOCK_DR / SETTLE_DR: funds locked at a fixed FX rate. */
  public static final UUID FX_LOCK =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  private SystemAccounts() {
  }
}
