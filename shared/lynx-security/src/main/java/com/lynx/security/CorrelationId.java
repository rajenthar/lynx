package com.lynx.security;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Accessor for the current request's correlation id.
 *
 * <p>The id lives in the SLF4J {@link MDC} (mapped diagnostic context) under
 * {@link #MDC_KEY}, so every log line emitted while handling a request automatically
 * carries it — one grep reconstructs the full request path across services.
 * {@link CorrelationIdFilter} populates and clears it per request.
 */
public final class CorrelationId {

  /** MDC key; reference it in the log pattern as {@code %X{correlationId}}. */
  public static final String MDC_KEY = "correlationId";

  public static String current() {
    return MDC.get(MDC_KEY);
  }

  static void set(String correlationId) {
    MDC.put(MDC_KEY, correlationId);
  }

  static void clear() {
    MDC.remove(MDC_KEY);
  }

  static String generate() {
    return UUID.randomUUID().toString();
  }

  private CorrelationId() {
  }
}
