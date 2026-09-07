package com.lynx.ledger.domain;

/**
 * The four ADR-001 saga phases `ledger-service` executes. Doubles as the
 * operation-type discriminator passed to {@code IdempotencyGuard.execute}
 * (see other-docs/04 Decision 8 / other-docs/08 Decision 11) — using
 * {@code name()} instead of hand-typed string literals ("HOLD", "LOCK", ...)
 * removes the chance of a typo silently creating two different cache-key
 * discriminators for what was meant to be the same phase.
 */
public enum SagaPhase {
  HOLD,
  LOCK,
  SETTLE,
  RELEASE
}
