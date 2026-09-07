package com.lynx.telemetry;

import org.slf4j.MDC;

/**
 * MDC accessor for the saga a background thread is currently processing.
 *
 * <p>Mirrors {@code CorrelationId} in lynx-security, but for a different scope:
 * correlation-id is set once per HTTP request (by a servlet filter, request-thread
 * lifetime); saga-id is set once per MESSAGE a saga-orchestrator or Kafka consumer
 * processes (no servlet request involved at all — those threads are pool workers,
 * not request threads). Every log line emitted while processing a saga
 * automatically carries its saga_id via {@code %X{sagaId}} in the log pattern —
 * the same "one grep reconstructs the whole story" benefit correlation-id gives
 * HTTP requests, but for saga processing instead.
 *
 * <p>Callers MUST clear in a {@code finally} block — see {@link MdcScope} for a
 * reusable, {@code try}-with-resources way to do that instead of hand-rolling
 * try/finally at every call site (real usage: {@code LedgerController}).
 */
public final class SagaMdc {

  /** MDC key; reference it in the log pattern as {@code %X{sagaId}}. */
  public static final String MDC_KEY = "sagaId";

  public static String current() {
    return MDC.get(MDC_KEY);
  }

  public static void set(String sagaId) {
    MDC.put(MDC_KEY, sagaId);
  }

  public static void clear() {
    MDC.remove(MDC_KEY);
  }

  private SagaMdc() {
  }
}
