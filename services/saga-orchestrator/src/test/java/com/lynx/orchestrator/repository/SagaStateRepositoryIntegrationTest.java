package com.lynx.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.lynx.orchestrator.client.FxRateServiceClient;
import com.lynx.orchestrator.client.LedgerServiceClient;
import com.lynx.orchestrator.client.dto.FxQuoteResult;
import com.lynx.orchestrator.domain.SagaState;
import com.lynx.orchestrator.domain.SagaStatus;
import com.lynx.orchestrator.service.SagaOrchestratorService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres 16 via Testcontainers, real Flyway migration, real
 * {@code FOR UPDATE SKIP LOCKED} — the genuine proof other-docs/10's plan
 * (and DECISIONS.md's corrected orchestrator-loop note) promised: two
 * concurrent "instances" claiming batches at once must get DISJOINT rows,
 * neither blocking on the other.
 */
@Testcontainers
@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "lynx.scheduler.enabled=false"
})
class SagaStateRepositoryIntegrationTest {

  @MockBean
  private LedgerServiceClient ledgerServiceClient;

  @MockBean
  private FxRateServiceClient fxRateServiceClient;

  @Autowired
  private SagaOrchestratorService sagaOrchestratorService;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("lynx")
          .withUsername("lynx")
          .withPassword("lynx");

  @DynamicPropertySource
  static void datasourceProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private SagaStateRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  private TransactionTemplate transactionTemplate;

  @BeforeEach
  void setUp() {
    transactionTemplate = new TransactionTemplate(transactionManager);
    transactionTemplate.executeWithoutResult(status ->
        repository.deleteAll());
  }

  private static SagaState newHoldingSaga() {
    return new SagaState(UUID.randomUUID(), "user-1", UUID.randomUUID(), UUID.randomUUID(),
        new BigDecimal("100.00"), "SGD", "USD", null);
  }

  @Test
  void claimBatchOnlyReturnsRowsAtTheRequestedStatus() {
    SagaState holding = newHoldingSaga();
    SagaState locked = newHoldingSaga();
    locked.setStatus(SagaStatus.LOCKED);
    transactionTemplate.executeWithoutResult(status -> repository.saveAllAndFlush(List.of(holding, locked)));

    List<SagaState> claimed = transactionTemplate.execute(status ->
        repository.claimBatch("HOLDING", 100));

    assertThat(claimed).extracting(SagaState::getSagaId).containsExactly(holding.getSagaId());
  }

  @Test
  void twoConcurrentClaimsOfTheSameStatusGetDisjointBatches() throws Exception {
    // 10 HOLDING sagas, LIMIT 2 per claim — two "instances" (separate
    // threads, separate transactions) claim concurrently. Real FOR UPDATE
    // SKIP LOCKED means neither blocks on the other; the second instance
    // gets the NEXT available rows, not the same ones, and not an
    // exception.
    List<SagaState> sagas = java.util.stream.IntStream.range(0, 10)
        .mapToObj(i -> newHoldingSaga())
        .toList();
    transactionTemplate.executeWithoutResult(status -> repository.saveAllAndFlush(sagas));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch bothStarted = new CountDownLatch(2);
    try {
      var future1 = pool.submit(() -> claimHoldingBatch(bothStarted, 2));
      var future2 = pool.submit(() -> claimHoldingBatch(bothStarted, 2));

      List<UUID> claimedByOne = future1.get(10, TimeUnit.SECONDS);
      List<UUID> claimedByTwo = future2.get(10, TimeUnit.SECONDS);

      assertThat(claimedByOne).hasSize(2);
      assertThat(claimedByTwo).hasSize(2);
      assertThat(claimedByOne).doesNotContainAnyElementsOf(claimedByTwo);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Claims a batch and holds the transaction open briefly (simulating real
   * per-saga processing work) before committing — long enough that, if
   * this were plain {@code FOR UPDATE} instead of {@code SKIP LOCKED}, the
   * other thread's claim would visibly block on it for that same window.
   */
  private List<UUID> claimHoldingBatch(CountDownLatch bothStarted, int limit) {
    bothStarted.countDown();
    try {
      bothStarted.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return transactionTemplate.execute(status -> {
      List<SagaState> claimed = repository.claimBatch("HOLDING", limit);
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return claimed.stream().map(SagaState::getSagaId).toList();
    });
  }

  @Test
  void claimStaleOnlyReturnsNonTerminalSagasPastTheCutoff() {
    SagaState fresh = newHoldingSaga();
    SagaState stale = newHoldingSaga();
    SagaState settled = newHoldingSaga();
    settled.setStatus(SagaStatus.SETTLED);
    transactionTemplate.executeWithoutResult(status ->
        repository.saveAllAndFlush(List.of(fresh, stale, settled)));

    // Backdate `stale`'s updated_at directly via a native UPDATE — the
    // entity's own setStatus(...) always stamps "now", so this is the only
    // way to simulate a saga that's genuinely been sitting untouched.
    transactionTemplate.executeWithoutResult(status ->
        repository.backdateUpdatedAtForTest(stale.getSagaId(), Instant.now().minusSeconds(600)));

    List<SagaState> claimed = transactionTemplate.execute(status ->
        repository.claimStale(Instant.now().minusSeconds(300), 100));

    assertThat(claimed).extracting(SagaState::getSagaId).containsExactly(stale.getSagaId());
  }

  @Test
  void processBatchActuallyPersistsTheStatusTransitionNotJustTheInMemoryObject() {
    // SagaOrchestratorService's step methods (quoteAndLock/executeTrade/
    // settle/compensate) never call repository.save(...) explicitly — they
    // rely on claimBatch's returned entities being MANAGED (Hibernate
    // dirty-checking flushes the mutation at commit) rather than detached.
    // SagaOrchestratorServiceTest only proves this with a MOCKED
    // repository, which can't prove persistence at all — this is the one
    // test that actually re-reads the row, in a SEPARATE transaction,
    // after processBatch's own transaction has committed.
    SagaState saga = newHoldingSaga();
    transactionTemplate.executeWithoutResult(status -> repository.saveAndFlush(saga));

    when(fxRateServiceClient.quote(any(), anyString(), anyString()))
        .thenReturn(new FxQuoteResult(new BigDecimal("0.7412"), Instant.now().plusSeconds(60)));

    sagaOrchestratorService.processBatch(SagaStatus.HOLDING);

    SagaState reread = transactionTemplate.execute(status ->
        repository.findBySagaIdAndUserId(saga.getSagaId(), "user-1").orElseThrow());
    assertThat(reread.getStatus()).isEqualTo(SagaStatus.LOCKED);
    assertThat(reread.getRate()).isEqualByComparingTo("0.7412");
  }
}
