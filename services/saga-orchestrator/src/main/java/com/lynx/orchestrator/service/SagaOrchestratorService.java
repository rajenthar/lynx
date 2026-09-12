package com.lynx.orchestrator.service;

import com.lynx.common.error.NotFoundException;
import com.lynx.money.Money;
import com.lynx.orchestrator.client.DownstreamUnavailableException;
import com.lynx.orchestrator.client.FxRateServiceClient;
import com.lynx.orchestrator.client.LedgerServiceClient;
import com.lynx.orchestrator.client.SagaStepFailedException;
import com.lynx.orchestrator.client.dto.FxExecutionResult;
import com.lynx.orchestrator.client.dto.FxQuoteResult;
import com.lynx.orchestrator.domain.SagaState;
import com.lynx.orchestrator.domain.SagaStatus;
import com.lynx.orchestrator.repository.SagaStateRepository;
import com.lynx.telemetry.MdcScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives one saga from {@code HOLDING} to {@code SETTLED} (or {@code FAILED}),
 * one phase at a time, per other-docs/10's plan. Called from
 * {@link com.lynx.orchestrator.scheduler.SagaPollingScheduler}'s
 * {@code @Scheduled} methods, never directly by a client request — a batch
 * IS one transaction (the {@code FOR UPDATE SKIP LOCKED} claim's lock only
 * makes sense for the transaction's lifetime).
 */
@Service
public class SagaOrchestratorService {

  private static final Logger log = LoggerFactory.getLogger(SagaOrchestratorService.class);

  /** Matches ADR-003's own plan (LIMIT 100 per batch). */
  static final int BATCH_SIZE = 100;

  /** Matches ADR-003/DECISIONS.md's recovery-worker SLA. */
  static final java.time.Duration STALE_AFTER = java.time.Duration.ofMinutes(5);

  private final SagaStateRepository repository;
  private final LedgerServiceClient ledgerServiceClient;
  private final FxRateServiceClient fxRateServiceClient;

  public SagaOrchestratorService(SagaStateRepository repository,
                                  LedgerServiceClient ledgerServiceClient,
                                  FxRateServiceClient fxRateServiceClient) {
    this.repository = repository;
    this.ledgerServiceClient = ledgerServiceClient;
    this.fxRateServiceClient = fxRateServiceClient;
  }

  /**
   * The one internal, service-to-service entry point (other-docs/10
   * Decision 3) — called by whatever already decided this saga should
   * exist (a future {@code transaction-service}), never by an end-user
   * request directly. Idempotent on {@code (sagaId, userId)}, not
   * {@code sagaId} alone (other-docs/10 Decision 7 — {@code sagaId} can
   * theoretically collide between two unrelated users; scoping by
   * {@code userId} too means a collision surfaces as a harmless,
   * coexisting extra row instead of one caller silently being handed
   * someone else's saga and never actually holding their own funds).
   * {@code ledger-service}'s own {@code hold} is already idempotent per
   * {@code (userId, sagaId, HOLD)}, so a genuine race here (two concurrent
   * calls for the same brand-new sagaId+userId) is harmless either way;
   * this check just avoids a redundant HTTP call in the common case.
   *
   * <p>No {@link MdcScope} here — deliberately. Every real caller is
   * {@code SagaController.create(...)}, which already opens one for the
   * whole request; {@code MdcScope} explicitly doesn't support nesting
   * (a second scope closing early would clear the outer one's value), so
   * this method relies on its caller instead of opening its own. Contrast
   * {@link #processOne}, which DOES open one — it's driven by the
   * scheduler, not a request, so there's no outer scope to rely on there.
   */
  @Transactional
  public SagaState createSaga(UUID sagaId, String userId, UUID fromAccountId, UUID toAccountId,
                               BigDecimal amount, String fromCurrency, String toCurrency,
                               UUID retriedFromSagaId) {
    var existing = repository.findBySagaIdAndUserId(sagaId, userId);
    if (existing.isPresent()) {
      log.info("createSaga called again for an existing saga {} — returning it unchanged", sagaId);
      return existing.get();
    }

    ledgerServiceClient.hold(sagaId, userId, fromAccountId, amount, fromCurrency);

    SagaState saga = new SagaState(sagaId, userId, fromAccountId, toAccountId,
        amount, fromCurrency, toCurrency, retriedFromSagaId);
    try {
      repository.saveAndFlush(saga);
    } catch (DataIntegrityViolationException raceOnInsert) {
      // A concurrent duplicate call for this exact (sagaId, userId) beat
      // us to the insert — ledger-service's own hold above already made
      // that race harmless (idempotent per sagaId+userId), so just
      // return the row the other call created.
      log.info("Concurrent createSaga for saga {} — returning the winner's row", sagaId);
      return repository.findBySagaIdAndUserId(sagaId, userId).orElseThrow();
    }
    log.info("Saga {} created for userId={}, status=HOLDING", sagaId, userId);
    return saga;
  }

  /**
   * Plain read — GET, no idempotency guard, same reasoning as
   * ledger-service's audit trail. Scoped to {@code (sagaId, userId)} for
   * the same collision reason as {@link #createSaga} (other-docs/10
   * Decision 7) — a saga belonging to a different user reads back as this
   * same 404, same "don't confirm existence to a non-owner" reasoning as
   * ledger-service's own scoped reads (other-docs/08 Decision 31).
   */
  public SagaState findOrThrow(UUID sagaId, String userId) {
    return repository.findBySagaIdAndUserId(sagaId, userId)
        .orElseThrow(() -> new NotFoundException("No saga found: " + sagaId));
  }

  /** One batch, one transaction — the claim's row lock lives exactly as long as this method runs. */
  @Transactional
  public void processBatch(SagaStatus status) {
    List<SagaState> batch = repository.claimBatch(status.name(), BATCH_SIZE);
    for (SagaState saga : batch) {
      processOne(saga);
    }
  }

  /** The recovery worker — same processing, a different claim query (see {@code SagaStateRepository}). */
  @Transactional
  public void processStale() {
    List<SagaState> stale = repository.claimStale(Instant.now().minus(STALE_AFTER), BATCH_SIZE);
    for (SagaState saga : stale) {
      log.warn("Recovery worker resuming stuck saga {} at status {} (stale since {})",
          saga.getSagaId(), saga.getStatus(), saga.getUpdatedAt());
      processOne(saga);
    }
  }

  private void processOne(SagaState saga) {
    try (MdcScope ignored = MdcScope.forSaga(saga.getSagaId().toString())) {
      try {
        switch (saga.getStatus()) {
          case HOLDING -> quoteAndLock(saga);
          case LOCKED -> executeTrade(saga);
          case EXECUTED -> settle(saga);
          case SETTLED, FAILED -> log.warn(
              "Saga {} claimed at terminal status {} — should be unreachable via the claim queries, no-op",
              saga.getSagaId(), saga.getStatus());
        }
      } catch (SagaStepFailedException e) {
        compensate(saga, e.getMessage());
      } catch (DownstreamUnavailableException e) {
        // Deliberately no mutation here — other-docs/10's confirmed
        // decision: a transient failure leaves the saga at its CURRENT
        // status, untouched. The row lock releases at this transaction's
        // commit either way; the next poll (or the recovery worker, if
        // this keeps failing) simply claims it again and retries the
        // SAME step, safe because every downstream call is idempotent.
        log.warn("Transient failure processing saga {} at status {} — leaving for retry: {}",
            saga.getSagaId(), saga.getStatus(), e.getMessage());
      }
    }
  }

  private void quoteAndLock(SagaState saga) {
    FxQuoteResult quote = fxRateServiceClient.quote(
        saga.getAmount(), saga.getFromCurrency(), saga.getToCurrency());
    ledgerServiceClient.lock(saga.getSagaId(), saga.getUserId(), saga.getAmount(), saga.getFromCurrency(),
        saga.getFromCurrency(), saga.getToCurrency(), quote.rate(), quote.expiresAt());

    saga.setRate(quote.rate());
    saga.setRateExpiresAt(quote.expiresAt());
    // Computed once, here — every later redo of executeTrade() reads this
    // stored value, never re-derives it (see ExecutionIds' javadoc).
    saga.setExecutionId(ExecutionIds.deriveExecutionId(saga.getSagaId()));
    saga.setStatus(SagaStatus.LOCKED);
    repository.saveAndFlush(saga);
    log.info("Saga {} locked: {} {} -> {} @ {}, expires {}", saga.getSagaId(),
        saga.getAmount(), saga.getFromCurrency(), saga.getToCurrency(), quote.rate(), quote.expiresAt());
  }

  private void executeTrade(SagaState saga) {
    if (saga.getRateExpiresAt() != null && Instant.now().isAfter(saga.getRateExpiresAt())) {
      // ADR-003's rate-lock expiry policy (Option B, decided): release only,
      // never silently re-lock the same saga. A retry, if wanted, is a
      // brand-new saga — out of scope here (other-docs/10 Decision 2).
      throw new SagaStepFailedException("Locked rate expired before execution (expired at "
          + saga.getRateExpiresAt() + ")");
    }
    FxExecutionResult result = fxRateServiceClient.execute(saga.getExecutionId(), saga.getSagaId(),
        saga.getAmount(), saga.getFromCurrency(), saga.getToCurrency(), saga.getRate());
    if (!result.succeeded()) {
      throw new SagaStepFailedException("FX execution failed: " + result.failureReason());
    }
    saga.setFilledRate(result.filledRate());
    saga.setStatus(SagaStatus.EXECUTED);
    repository.saveAndFlush(saga);
    log.info("Saga {} executed at filledRate={}", saga.getSagaId(), result.filledRate());
  }

  private void settle(SagaState saga) {
    // SETTLE credits using EXECUTE's actual filledRate, not the original
    // quoted rate — other-docs/10 Decision 1: today's MockFxProvider always
    // fills at exactly the requested rate, so there's no visible difference
    // yet, but filledRate is the value that's actually true.
    Money debited = Money.of(saga.getAmount(), Money.currencyOf(saga.getFromCurrency()));
    Money credited = debited.convert(Money.currencyOf(saga.getToCurrency()), saga.getFilledRate());

    ledgerServiceClient.settle(saga.getSagaId(), saga.getUserId(),
        saga.getFromAccountId(), saga.getToAccountId(),
        debited.amount(), saga.getFromCurrency(),
        credited.amount(), saga.getToCurrency());

    saga.setStatus(SagaStatus.SETTLED);
    repository.saveAndFlush(saga);
    log.info("Saga {} settled: {} {} credited to {}",
        saga.getSagaId(), credited.amount(), saga.getToCurrency(), saga.getToAccountId());
  }

  /**
   * Compensation (ADR-003, explicit) — releases whatever this saga is
   * currently holding and marks it {@code FAILED}. If release ITSELF fails
   * transiently, the saga is deliberately left unchanged at its CURRENT
   * (pre-failure) status: the recovery worker resumes from there, re-runs
   * the same step, hits the same {@link SagaStepFailedException} again, and
   * retries release again — self-healing, no extra state needed.
   */
  private void compensate(SagaState saga, String reason) {
    try {
      ledgerServiceClient.release(saga.getSagaId(), saga.getUserId(),
          saga.getFromAccountId(), saga.getAmount(), saga.getFromCurrency(), reason);
      saga.setFailureReason(reason);
      saga.setStatus(SagaStatus.FAILED);
      repository.saveAndFlush(saga);
      log.warn("Saga {} failed and released: {}", saga.getSagaId(), reason);
    } catch (DownstreamUnavailableException releaseUnreachable) {
      log.warn("Release itself unreachable for saga {} (failure reason: {}) — "
          + "leaving for the recovery worker to retry", saga.getSagaId(), reason);
    }
  }
}
