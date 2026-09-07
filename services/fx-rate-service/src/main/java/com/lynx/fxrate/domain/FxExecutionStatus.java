package com.lynx.fxrate.domain;

/**
 * Terminal outcome of one FX execution attempt. Deliberately only two
 * states, both terminal — {@link com.lynx.fxrate.provider.FxProvider}
 * decides synchronously, so there is no durable "pending" state at rest.
 *
 * <p>The real uncertainty this service protects against isn't "is the
 * provider still deciding" — it's "did the CALLER's request reach this
 * service and get recorded, when the caller never saw the response."
 * That's resolved by idempotent retry (same {@code executionId} — see
 * {@link com.lynx.fxrate.service.FxExecutionService}), not by a pending
 * status here.
 */
public enum FxExecutionStatus {
  EXECUTED,
  FAILED
}
