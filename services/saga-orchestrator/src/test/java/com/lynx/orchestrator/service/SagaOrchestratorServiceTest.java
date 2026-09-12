package com.lynx.orchestrator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.common.error.NotFoundException;
import com.lynx.orchestrator.client.DownstreamUnavailableException;
import com.lynx.orchestrator.client.FxRateServiceClient;
import com.lynx.orchestrator.client.LedgerServiceClient;
import com.lynx.orchestrator.client.SagaStepFailedException;
import com.lynx.orchestrator.client.dto.FxExecutionResult;
import com.lynx.orchestrator.client.dto.FxQuoteResult;
import com.lynx.orchestrator.domain.SagaState;
import com.lynx.orchestrator.domain.SagaStatus;
import com.lynx.orchestrator.repository.SagaStateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mocked repository + clients, no Postgres: proves the state machine's
 * transitions, the deterministic executionId's stability across redos, the
 * transient-vs-business-failure split (other-docs/10's confirmed decision),
 * and settle crediting from filledRate rather than the original quote.
 */
class SagaOrchestratorServiceTest {

  private SagaStateRepository repository;
  private LedgerServiceClient ledgerServiceClient;
  private FxRateServiceClient fxRateServiceClient;
  private SagaOrchestratorService service;

  @BeforeEach
  void setUp() {
    repository = mock(SagaStateRepository.class);
    ledgerServiceClient = mock(LedgerServiceClient.class);
    fxRateServiceClient = mock(FxRateServiceClient.class);
    service = new SagaOrchestratorService(repository, ledgerServiceClient, fxRateServiceClient);

    when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void createSagaHoldsThenInsertsWithStatusHolding() {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();
    UUID toAccount = UUID.randomUUID();
    when(repository.findBySagaIdAndUserId(sagaId, "user-1")).thenReturn(Optional.empty());

    SagaState saga = service.createSaga(sagaId, "user-1", fromAccount, toAccount,
        new BigDecimal("100.00"), "SGD", "USD", null);

    verify(ledgerServiceClient).hold(sagaId, "user-1", fromAccount, new BigDecimal("100.00"), "SGD");
    assertEquals(SagaStatus.HOLDING, saga.getStatus());
    assertEquals("user-1", saga.getUserId());
  }

  @Test
  void createSagaIsIdempotentForAnAlreadyExistingSaga() {
    UUID sagaId = UUID.randomUUID();
    SagaState existing = new SagaState(sagaId, "user-1", UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("100.00"), "SGD", "USD", null);
    when(repository.findBySagaIdAndUserId(sagaId, "user-1")).thenReturn(Optional.of(existing));

    SagaState result = service.createSaga(sagaId, "user-1", UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("100.00"), "SGD", "USD", null);

    assertEquals(existing, result);
    verify(ledgerServiceClient, never()).hold(any(), anyString(), any(), any(), anyString());
  }

  @Test
  void holdingSagaGetsQuotedAndLockedWithADeterministicExecutionId() {
    SagaState saga = holdingSaga();
    Instant expiresAt = Instant.now().plusSeconds(60);
    when(fxRateServiceClient.quote(saga.getAmount(), "SGD", "USD"))
        .thenReturn(new FxQuoteResult(new BigDecimal("0.7412"), expiresAt));
    when(repository.claimBatch("HOLDING", SagaOrchestratorService.BATCH_SIZE)).thenReturn(List.of(saga));

    service.processBatch(SagaStatus.HOLDING);

    verify(ledgerServiceClient).lock(saga.getSagaId(), "user-1", saga.getAmount(), "SGD",
        "SGD", "USD", new BigDecimal("0.7412"), expiresAt);
    assertEquals(SagaStatus.LOCKED, saga.getStatus());
    assertEquals(new BigDecimal("0.7412"), saga.getRate());
    assertEquals(expiresAt, saga.getRateExpiresAt());
    assertNotNull(saga.getExecutionId());
  }

  @Test
  void redoOfTheLockStepWouldDeriveTheSameExecutionIdEveryTime() {
    // Not exercised through processBatch (redo of an already-LOCKED saga
    // never re-enters quoteAndLock), but the derivation itself must be
    // stable — this is the guarantee ExecutionIds exists to provide.
    UUID sagaId = UUID.randomUUID();
    assertEquals(ExecutionIds.deriveExecutionId(sagaId), ExecutionIds.deriveExecutionId(sagaId));
  }

  @Test
  void lockedSagaGetsExecutedAndStoresTheFilledRate() {
    SagaState saga = lockedSaga();
    when(fxRateServiceClient.execute(saga.getExecutionId(), saga.getSagaId(), saga.getAmount(),
        "SGD", "USD", saga.getRate()))
        .thenReturn(new FxExecutionResult("EXECUTED", new BigDecimal("0.7400"), null));
    when(repository.claimBatch("LOCKED", SagaOrchestratorService.BATCH_SIZE)).thenReturn(List.of(saga));

    service.processBatch(SagaStatus.LOCKED);

    assertEquals(SagaStatus.EXECUTED, saga.getStatus());
    assertEquals(new BigDecimal("0.7400"), saga.getFilledRate());
  }

  @Test
  void executedSagaSettlesUsingTheFilledRateNotTheOriginalQuote() {
    SagaState saga = executedSaga(new BigDecimal("0.7400")); // filledRate differs from the quoted 0.7412
    when(repository.claimBatch("EXECUTED", SagaOrchestratorService.BATCH_SIZE)).thenReturn(List.of(saga));

    service.processBatch(SagaStatus.EXECUTED);

    verify(ledgerServiceClient).settle(
        eq(saga.getSagaId()), eq("user-1"), eq(saga.getFromAccountId()), eq(saga.getToAccountId()),
        eq(new BigDecimal("100.00")), eq("SGD"),
        eq(new BigDecimal("74.00")), eq("USD"));
    assertEquals(SagaStatus.SETTLED, saga.getStatus());
  }

  @Test
  void anExpiredLockAtExecuteTimeFailsAndReleasesRatherThanRetrying() {
    SagaState saga = lockedSaga();
    saga.setRateExpiresAt(Instant.now().minusSeconds(1)); // already expired
    when(repository.claimBatch("LOCKED", SagaOrchestratorService.BATCH_SIZE)).thenReturn(List.of(saga));

    service.processBatch(SagaStatus.LOCKED);

    verify(fxRateServiceClient, never()).execute(any(), any(), any(), anyString(), anyString(), any());
    verify(ledgerServiceClient).release(eq(saga.getSagaId()), eq("user-1"), eq(saga.getFromAccountId()),
        eq(saga.getAmount()), eq("SGD"), anyString());
    assertEquals(SagaStatus.FAILED, saga.getStatus());
    assertNotNull(saga.getFailureReason());
  }

  @Test
  void aRealErrorResponseFailsTheSagaWithoutRetrying() {
    SagaState saga = lockedSaga();
    when(fxRateServiceClient.execute(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new FxExecutionResult("FAILED", null, "provider rejected the trade"));
    when(repository.claimBatch("LOCKED", SagaOrchestratorService.BATCH_SIZE)).thenReturn(List.of(saga));

    service.processBatch(SagaStatus.LOCKED);

    verify(ledgerServiceClient).release(eq(saga.getSagaId()), eq("user-1"), any(), any(), anyString(), anyString());
    assertEquals(SagaStatus.FAILED, saga.getStatus());
  }

  @Test
  void aTransientDownstreamFailureLeavesTheSagaUntouchedForRetry() {
    SagaState saga = holdingSaga();
    when(fxRateServiceClient.quote(any(), anyString(), anyString()))
        .thenThrow(new DownstreamUnavailableException("connection refused"));
    when(repository.claimBatch("HOLDING", SagaOrchestratorService.BATCH_SIZE)).thenReturn(List.of(saga));

    service.processBatch(SagaStatus.HOLDING);

    assertEquals(SagaStatus.HOLDING, saga.getStatus(), "must NOT advance or fail on a transient error");
    verify(ledgerServiceClient, never()).release(any(), anyString(), any(), any(), anyString(), anyString());
  }

  @Test
  void ifReleaseItselfIsUnreachableTheSagaIsLeftForTheRecoveryWorker() {
    SagaState saga = lockedSaga();
    saga.setRateExpiresAt(Instant.now().minusSeconds(1));
    org.mockito.Mockito.doThrow(new DownstreamUnavailableException("ledger-service unreachable"))
        .when(ledgerServiceClient).release(any(), anyString(), any(), any(), anyString(), anyString());
    when(repository.claimBatch("LOCKED", SagaOrchestratorService.BATCH_SIZE)).thenReturn(List.of(saga));

    service.processBatch(SagaStatus.LOCKED);

    assertEquals(SagaStatus.LOCKED, saga.getStatus(), "stays at its pre-failure status, not FAILED");
  }

  @Test
  void findOrThrowThrowsNotFoundForAnUnknownSaga() {
    UUID sagaId = UUID.randomUUID();
    when(repository.findBySagaIdAndUserId(sagaId, "user-1")).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.findOrThrow(sagaId, "user-1"));
  }

  @Test
  void findOrThrowThrowsNotFoundWhenTheSagaBelongsToADifferentUser() {
    // other-docs/10 Decision 7: same 404, indistinguishable from a saga
    // that never existed — this endpoint can't be used to probe for other
    // users' saga ids.
    UUID sagaId = UUID.randomUUID();
    when(repository.findBySagaIdAndUserId(sagaId, "user-2")).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.findOrThrow(sagaId, "user-2"));
  }

  @Test
  void recoveryWorkerResumesAStaleSagaAtItsCurrentStatus() {
    SagaState saga = lockedSaga();
    when(fxRateServiceClient.execute(any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new FxExecutionResult("EXECUTED", saga.getRate(), null));
    when(repository.claimStale(any(), eq(SagaOrchestratorService.BATCH_SIZE))).thenReturn(List.of(saga));

    service.processStale();

    assertEquals(SagaStatus.EXECUTED, saga.getStatus());
  }

  private static SagaState holdingSaga() {
    return new SagaState(UUID.randomUUID(), "user-1", UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("100.00"), "SGD", "USD", null);
  }

  private static SagaState lockedSaga() {
    SagaState saga = holdingSaga();
    saga.setRate(new BigDecimal("0.7412"));
    saga.setRateExpiresAt(Instant.now().plusSeconds(60));
    saga.setExecutionId(ExecutionIds.deriveExecutionId(saga.getSagaId()));
    saga.setStatus(SagaStatus.LOCKED);
    return saga;
  }

  private static SagaState executedSaga(BigDecimal filledRate) {
    SagaState saga = lockedSaga();
    saga.setFilledRate(filledRate);
    saga.setStatus(SagaStatus.EXECUTED);
    return saga;
  }
}
