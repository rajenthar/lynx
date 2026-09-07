package com.lynx.fxrate.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.common.error.NotFoundException;
import com.lynx.fxrate.domain.FxExecution;
import com.lynx.fxrate.dto.FxExecutionRequest;
import com.lynx.fxrate.dto.FxExecutionResponse;
import com.lynx.fxrate.domain.FxExecutionStatus;
import com.lynx.fxrate.provider.FxFill;
import com.lynx.fxrate.provider.FxProvider;
import com.lynx.fxrate.repository.FxExecutionRepository;
import com.lynx.idempotency.IdempotencyGuard;
import com.lynx.idempotency.InMemoryIdempotencyCache;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mocked repository + mocked {@link FxProvider} — no Postgres. Proves the
 * idempotent-execution contract (reusing {@link IdempotencyGuard} exactly
 * as ledger-service does), the EXECUTED/FAILED mapping, and the 404 read
 * path — mirrors {@code LedgerServiceTest}'s own structure/conventions.
 */
class FxExecutionServiceTest {

  private FxExecutionRepository repository;
  private FxProvider fxProvider;
  private FxExecutionService service;

  @BeforeEach
  void setUp() {
    repository = mock(FxExecutionRepository.class);
    fxProvider = mock(FxProvider.class);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    IdempotencyGuard idempotencyGuard = new IdempotencyGuard(new InMemoryIdempotencyCache());
    service = new FxExecutionService(repository, fxProvider, idempotencyGuard);
  }

  private static FxExecutionRequest request(UUID executionId, UUID sagaId) {
    return new FxExecutionRequest(executionId, sagaId, new BigDecimal("100.00"),
        "SGD", "USD", new BigDecimal("0.7412"));
  }

  @Test
  void successfulExecutionReturnsExecutedWithTheFilledRate() {
    when(fxProvider.execute(any(), any(), any(), any()))
        .thenReturn(FxFill.executed(new BigDecimal("0.7412")));

    FxExecutionResponse response = service.execute(request(UUID.randomUUID(), UUID.randomUUID()));

    assertEquals(FxExecutionStatus.EXECUTED, response.status());
    assertEquals(new BigDecimal("0.7412"), response.filledRate());
    assertEquals(null, response.failureReason());
  }

  @Test
  void providerRejectionReturnsFailedWithTheReason() {
    when(fxProvider.execute(any(), any(), any(), any()))
        .thenReturn(FxFill.failed("Insufficient liquidity"));

    FxExecutionResponse response = service.execute(request(UUID.randomUUID(), UUID.randomUUID()));

    assertEquals(FxExecutionStatus.FAILED, response.status());
    assertEquals("Insufficient liquidity", response.failureReason());
    assertEquals(null, response.filledRate());
  }

  @Test
  void retryWithSameExecutionIdIsServedFromCacheWithoutCallingTheProviderAgain() {
    when(fxProvider.execute(any(), any(), any(), any()))
        .thenReturn(FxFill.executed(new BigDecimal("0.7412")));
    FxExecutionRequest request = request(UUID.randomUUID(), UUID.randomUUID());

    FxExecutionResponse first = service.execute(request);
    FxExecutionResponse second = service.execute(request);

    assertEquals(first, second);
    verify(fxProvider, times(1)).execute(any(), any(), any(), any());
  }

  @Test
  void getThrowsNotFoundWhenNoExecutionRecorded() {
    UUID executionId = UUID.randomUUID();
    when(repository.findByExecutionId(executionId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.get(executionId));
  }

  @Test
  void getReturnsTheRecordedExecution() {
    UUID executionId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    FxExecution entity = FxExecution.executed(executionId, sagaId, new BigDecimal("100.00"),
        "SGD", "USD", new BigDecimal("0.7412"), new BigDecimal("0.7412"));
    when(repository.findByExecutionId(executionId)).thenReturn(Optional.of(entity));

    FxExecutionResponse response = service.get(executionId);

    assertEquals(executionId, response.executionId());
    assertEquals(FxExecutionStatus.EXECUTED, response.status());
  }
}
