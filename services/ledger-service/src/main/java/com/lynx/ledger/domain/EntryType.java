package com.lynx.ledger.domain;

/**
 * Ledger leg discriminator: {@code <PHASE>_<DR|CR>}. Every saga phase writes
 * exactly one DR and one CR leg (ADR-001's double-entry invariant).
 */
public enum EntryType {
  HOLD_DR,
  HOLD_CR,
  LOCK_DR,
  LOCK_CR,
  SETTLE_DR,
  SETTLE_CR,
  RELEASE_DR,
  RELEASE_CR
}
