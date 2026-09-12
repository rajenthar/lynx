package com.lynx.orchestrator.domain;

/**
 * The saga's own state machine (ADR-003 plan, other-docs/10 Decision:
 * HOLD → QUOTE+LOCK → EXECUTE → SETTLE). {@code QUOTE} is not its own
 * status — nothing persists a quote on its own, so a saga is never
 * observed sitting "quoted but not yet locked"; {@code HOLDING} covers
 * both "just held" and "about to be quoted+locked."
 */
public enum SagaStatus {
  /** Funds held; next step is quote + lock. */
  HOLDING,
  /** Rate locked in the ledger; next step is the real FX execution. */
  LOCKED,
  /** FX trade executed; next step is crediting the recipient. */
  EXECUTED,
  /** Terminal — recipient credited. */
  SETTLED,
  /** Terminal — released, compensated. See {@code failureReason}. */
  FAILED
}
