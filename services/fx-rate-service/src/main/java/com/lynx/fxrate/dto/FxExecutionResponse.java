package com.lynx.fxrate.dto;

import com.lynx.fxrate.domain.FxExecutionStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record FxExecutionResponse(
    UUID executionId, UUID sagaId, FxExecutionStatus status,
    BigDecimal filledRate, String failureReason) {
}
