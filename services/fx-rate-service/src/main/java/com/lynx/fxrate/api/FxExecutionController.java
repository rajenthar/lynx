package com.lynx.fxrate.api;

import com.lynx.fxrate.dto.FxExecutionRequest;
import com.lynx.fxrate.dto.FxExecutionResponse;
import com.lynx.fxrate.service.FxExecutionService;
import com.lynx.telemetry.MdcScope;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * No auth wiring yet, deliberately — this service has no real caller
 * today (saga-orchestrator doesn't exist); when it does, it authenticates
 * exactly like ledger-service's own internal-service-caller path (ADR-007
 * Option C, {@code ServiceTokenProvider} + an {@code internal-service}
 * role check) — that mechanism already exists in {@code lynx-security},
 * ready to reuse, not redesigned here. Tracked as an open item, not an
 * oversight — see other-docs/09.
 */
@RestController
@RequestMapping("/v1/fx/executions")
public class FxExecutionController {

  private final FxExecutionService fxExecutionService;

  public FxExecutionController(FxExecutionService fxExecutionService) {
    this.fxExecutionService = fxExecutionService;
  }

  @PostMapping
  public FxExecutionResponse execute(@RequestBody FxExecutionRequest request) {
    try (MdcScope ignored = MdcScope.forSaga(request.sagaId().toString())) {
      return fxExecutionService.execute(request);
    }
  }

  @GetMapping("/{executionId}")
  public FxExecutionResponse get(@PathVariable UUID executionId) {
    return fxExecutionService.get(executionId);
  }
}
