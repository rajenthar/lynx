package com.lynx.orchestrator.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * The pieces of {@code fx-rate-service}'s {@code POST /v1/fx/executions}
 * response this service needs — mirrors {@code FxExecutionStatus}
 * (EXECUTED/FAILED) as a plain string rather than depending on
 * fx-rate-service's own enum class, since there is no shared client
 * library between these two services (each owns its own contract; see
 * {@code contracts/openapi/fx-rate-service.yaml}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FxExecutionResult(String status, BigDecimal filledRate, String failureReason) {

  public boolean succeeded() {
    return "EXECUTED".equals(status);
  }
}
