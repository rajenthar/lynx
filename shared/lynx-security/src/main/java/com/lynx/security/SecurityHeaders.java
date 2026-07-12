package com.lynx.security;

/** Canonical HTTP header names used across all Lynx services. */
public final class SecurityHeaders {

  /** Bearer token: {@code Authorization: Bearer <jwt>}. */
  public static final String AUTHORIZATION = "Authorization";

  /** Bearer scheme prefix (note the trailing space). */
  public static final String BEARER_PREFIX = "Bearer ";

  /**
   * Request-tracing id, propagated through every service hop.
   * See ADR-004: this is for tracing/observability, NEVER for idempotency.
   */
  public static final String CORRELATION_ID = "X-Correlation-ID";

  private SecurityHeaders() {}
}
