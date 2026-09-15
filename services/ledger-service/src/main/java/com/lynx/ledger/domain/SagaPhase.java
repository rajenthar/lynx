package com.lynx.ledger.domain;

/**
 * The operations {@code ledger-service} executes — the four ADR-001 saga
 * phases, plus {@code DEPOSIT} (other-docs/12), which isn't a saga phase at
 * all but reuses the exact same write-identity/idempotency plumbing (a
 * caller-supplied UUID + this enum's {@code name()} is a complete identity,
 * whether that UUID is a real {@code sagaId} or a client-generated
 * {@code depositId} — see {@code LedgerService#writePhase}). Doubles as the
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
  RELEASE,
  DEPOSIT
}
