package com.lynx.fxrate.service;

import com.lynx.common.error.NotFoundException;
import com.lynx.fxrate.domain.FxExecution;
import com.lynx.fxrate.dto.FxExecutionRequest;
import com.lynx.fxrate.dto.FxExecutionResponse;
import com.lynx.fxrate.provider.FxFill;
import com.lynx.fxrate.provider.FxProvider;
import com.lynx.fxrate.repository.FxExecutionRepository;
import com.lynx.idempotency.DuplicateRequestException;
import com.lynx.idempotency.IdempotencyGuard;
import com.lynx.idempotency.IdempotencyKey;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotent FX trade execution — reuses {@link IdempotencyGuard} exactly
 * as {@code ledger-service} does for its own saga phases (same mechanism,
 * a new operation), rather than inventing a parallel one.
 *
 * <p>No separate {@code userId}: this is a service-to-service call with no
 * live end-user behind it (see ADR-007's on-behalf-of pattern for the
 * general shape) — {@link #SYSTEM_CALLER} is a fixed placeholder cache-key
 * scope until this service has real callers to distinguish.
 *
 * <p>{@code executionId} doubles as both the idempotency key AND the
 * durable row's natural key ({@code UNIQUE(execution_id)}) — deliberately
 * no separate {@code Idempotency-Key} header, since a trade execution
 * request already has its own domain id.
 */
@Service
public class FxExecutionService {

  private static final Logger log = LoggerFactory.getLogger(FxExecutionService.class);

  private static final String SYSTEM_CALLER = "system";
  private static final String OPERATION_TYPE = "FX_EXECUTE";

  private final FxExecutionRepository repository;
  private final FxProvider fxProvider;
  private final IdempotencyGuard idempotencyGuard;

  public FxExecutionService(FxExecutionRepository repository, FxProvider fxProvider,
                             IdempotencyGuard idempotencyGuard) {
    this.repository = repository;
    this.fxProvider = fxProvider;
    this.idempotencyGuard = idempotencyGuard;
  }

  public FxExecutionResponse execute(FxExecutionRequest request) {
    IdempotencyKey idempotencyKey = IdempotencyKey.derivedFrom(request.executionId().toString());
    return idempotencyGuard.execute(
        SYSTEM_CALLER,
        idempotencyKey,
        OPERATION_TYPE,
        FxExecutionResponse.class,
        () -> doExecute(request),
        () -> recoverFromDb(request.executionId()));
  }

  private FxExecutionResponse doExecute(FxExecutionRequest request) {
    FxFill fill = fxProvider.execute(
        request.amount(), request.fromCurrency(), request.toCurrency(), request.requestedRate());

    FxExecution entity;
    if (fill.succeeded()) {
      entity = FxExecution.executed(request.executionId(), request.sagaId(), request.amount(),
          request.fromCurrency(), request.toCurrency(), request.requestedRate(), fill.filledRate());
      log.info("Executed saga {} executionId={}: {} {} -> {} @ {}",
          request.sagaId(), request.executionId(), request.amount(),
          request.fromCurrency(), request.toCurrency(), fill.filledRate());
    } else {
      entity = FxExecution.failed(request.executionId(), request.sagaId(), request.amount(),
          request.fromCurrency(), request.toCurrency(), request.requestedRate(), fill.failureReason());
      log.warn("Execution FAILED for saga {} executionId={}: {}",
          request.sagaId(), request.executionId(), fill.failureReason());
    }

    try {
      repository.save(entity);
    } catch (DataIntegrityViolationException e) {
      log.info("Duplicate execute for executionId={} — recovering the original result",
          request.executionId());
      throw new DuplicateRequestException(
          "FX execution already exists for executionId " + request.executionId(), e);
    }
    return toResponse(entity);
  }

  private Optional<FxExecutionResponse> recoverFromDb(UUID executionId) {
    return repository.findByExecutionId(executionId).map(FxExecutionService::toResponse);
  }

  /** Plain read — GET, no idempotency guard, same reasoning as ledger-service's audit trail. */
  public FxExecutionResponse get(UUID executionId) {
    return repository.findByExecutionId(executionId)
        .map(FxExecutionService::toResponse)
        .orElseThrow(() -> new NotFoundException("No FX execution found for id " + executionId));
  }

  private static FxExecutionResponse toResponse(FxExecution entity) {
    return new FxExecutionResponse(entity.getExecutionId(), entity.getSagaId(), entity.getStatus(),
        entity.getFilledRate(), entity.getFailureReason());
  }
}
