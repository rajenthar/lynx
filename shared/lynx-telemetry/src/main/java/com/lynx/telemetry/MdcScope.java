package com.lynx.telemetry;

/**
 * A {@code try}-with-resources-friendly wrapper around {@link SagaMdc#set}/
 * {@link SagaMdc#clear} — turns the repeated
 * {@code set(...); try { ... } finally { clear(); }} shape into one line.
 *
 * <pre>{@code
 * try (MdcScope ignored = MdcScope.forSaga(sagaId.toString())) {
 *   return ledgerService.hold(...);
 * }
 * }</pre>
 *
 * <p>Real usage: {@code LedgerController} previously hand-rolled the same
 * {@code set}/{@code try}/{@code finally { clear() }} block in all five of
 * its handler methods (one per saga phase, plus the audit-trail read) — this
 * class replaces that repetition. It intentionally does NOT support nesting
 * (a second {@code MdcScope} opened before the first is closed would clear
 * the first scope's value early); no genuine nesting scenario has come up
 * in this codebase, so that case is left unhandled rather than solved
 * speculatively.
 */
public final class MdcScope implements AutoCloseable {

  private MdcScope() {
  }

  /** Sets the saga id in the MDC; {@link #close} clears it. */
  public static MdcScope forSaga(String sagaId) {
    SagaMdc.set(sagaId);
    return new MdcScope();
  }

  @Override
  public void close() {
    SagaMdc.clear();
  }
}
