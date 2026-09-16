package com.lynx.orchestrator.client;

/**
 * A downstream call returned a genuine 4xx business-rule rejection —
 * insufficient funds, an unsupported currency, an expired lock discovered
 * at EXECUTE time. This is NOT
 * transient — retrying the SAME saga would just reproduce the same
 * failure. {@code SagaOrchestratorService.processOne} catches this and
 * compensates (releases, marks the saga {@code FAILED}) rather than
 * leaving it for the recovery worker to retry.
 */
public final class SagaStepFailedException extends RuntimeException {

  public SagaStepFailedException(String message) {
    super(message);
  }
}
