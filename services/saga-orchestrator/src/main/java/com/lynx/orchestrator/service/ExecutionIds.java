package com.lynx.orchestrator.service;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic {@code executionId} derivation — the same idea as
 * {@code lynx-idempotency}'s {@code SagaIds.deriveSagaId}, but one level
 * further down the chain: {@code executionId = nameUUID(sagaId + "|execute")}.
 *
 * <p>Computed once, the moment a saga reaches {@code LOCKED}
 * ({@code SagaOrchestratorService.quoteAndLock}), and stored on
 * {@code SagaState.executionId} from then on — {@code
 * SagaOrchestratorService.executeTrade} always reads the STORED value, it
 * never re-derives one. Deriving fresh from {@code sagaId} here (rather
 * than generating a random UUID) means a from-scratch redo that somehow
 * lost the stored value would still reproduce the SAME id — belt-and-braces
 * on top of the stored-value discipline, not a replacement for it.
 */
final class ExecutionIds {

  static UUID deriveExecutionId(UUID sagaId) {
    Objects.requireNonNull(sagaId, "sagaId");
    String composite = sagaId + "|execute";
    return UUID.nameUUIDFromBytes(composite.getBytes(StandardCharsets.UTF_8));
  }

  private ExecutionIds() {
  }
}
