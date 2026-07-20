package com.lynx.idempotency;

/** Canonical HTTP header names for idempotent request handling. */
public final class IdempotencyHeaders {

  /**
   * Client-generated request identity: {@code Idempotency-Key: <uuid>}.
   *
   * <p>The client generates it ONCE per logical request, stores it, and resends
   * the SAME value on every retry of that request (ADR-004). It is the input to
   * deterministic saga_id derivation — never confuse it with X-Correlation-ID,
   * which is tracing-only and never used for deduplication.
   */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private IdempotencyHeaders() {
  }
}
