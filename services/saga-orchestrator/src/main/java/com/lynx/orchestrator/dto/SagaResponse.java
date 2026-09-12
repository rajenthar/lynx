package com.lynx.orchestrator.dto;

import com.lynx.orchestrator.domain.SagaState;
import com.lynx.orchestrator.domain.SagaStatus;
import java.time.Instant;
import java.util.UUID;

/** Read-only view of a {@link SagaState} row — status lookup, not a command result. */
public record SagaResponse(
    UUID sagaId,
    SagaStatus status,
    String failureReason,
    Instant createdAt,
    Instant updatedAt) {

  public static SagaResponse of(SagaState saga) {
    return new SagaResponse(
        saga.getSagaId(), saga.getStatus(), saga.getFailureReason(),
        saga.getCreatedAt(), saga.getUpdatedAt());
  }
}
