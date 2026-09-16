package com.lynx.transaction.client;

/**
 * A downstream call returned a genuine 4xx rejection — a recipient with no
 * account in that currency (404 from {@code account-service}'s resolve
 * endpoint), a nonexistent/not-owned saga (404 from {@code
 * saga-orchestrator}), or similar. Unlike {@code saga-orchestrator}'s own
 * {@code SagaStepFailedException} (which always means "compensate and fail
 * the saga" — there's exactly one reasonable reaction there), this one
 * carries the actual HTTP status so {@code TransactionService} can
 * translate different 4xxs into different client-facing errors (a missing
 * recipient account is a 404, not a 400).
 */
public final class DownstreamRejectedException extends RuntimeException {

  private final int statusCode;

  public DownstreamRejectedException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
