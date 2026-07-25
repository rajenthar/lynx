package com.lynx.events;

/**
 * An event envelope could not be encoded or decoded.
 *
 * <p>Infrastructure/programmer error (malformed JSON, unknown {@link EventType},
 * a payload that no longer matches its declared shape) — never client-facing,
 * so deliberately NOT part of lynx-common's sealed LynxException hierarchy
 * (same split as {@code MoneyException}: this module has no client boundary at all).
 */
public final class EventCodecException extends RuntimeException {

  public EventCodecException(String message, Throwable cause) {
    super(message, cause);
  }
}
