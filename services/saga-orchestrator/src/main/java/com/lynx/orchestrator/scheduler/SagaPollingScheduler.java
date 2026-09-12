package com.lynx.orchestrator.scheduler;

import com.lynx.orchestrator.domain.SagaStatus;
import com.lynx.orchestrator.service.SagaOrchestratorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-003's polling loop, translated into three independent
 * {@code @Scheduled} methods (one per non-terminal, claimable status) plus
 * the recovery worker — matches DECISIONS.md's note that this must be
 * "one query PER PHASE (never combined + re-checked)," not one query
 * spanning multiple statuses.
 *
 * <p>100ms fixed delay per phase, 30s for recovery — the exact intervals
 * ADR-003/DECISIONS.md specify. {@code fixedDelay} (not
 * {@code fixedRate}): the next run is scheduled 100ms after the PREVIOUS
 * one finishes, not on a strict wall-clock cadence — deliberately, so a
 * slow batch never causes overlapping concurrent runs of the SAME method
 * inside one instance (Spring's default single-threaded scheduler already
 * prevents that for one method, but {@code fixedDelay} makes the intent
 * explicit rather than relying on that default).
 *
 * <p>{@code @ConditionalOnProperty}: a repository-level integration test
 * (see {@code SagaStateRepositoryIntegrationTest}) boots the real
 * application context to get a real, transactional
 * {@code SagaStateRepository} — it sets {@code lynx.scheduler.enabled=false}
 * so this component's own background polling doesn't run concurrently
 * against the test's manually-controlled rows and Testcontainers-backed
 * connection pool (which the test closes before the context does,
 * otherwise leaving a background thread hammering a dead connection).
 * {@code matchIfMissing = true}: the real service still runs it by default
 * with no property set at all.
 */
@Component
@ConditionalOnProperty(name = "lynx.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SagaPollingScheduler {

  private final SagaOrchestratorService service;

  public SagaPollingScheduler(SagaOrchestratorService service) {
    this.service = service;
  }

  @Scheduled(fixedDelay = 100)
  public void pollHolding() {
    service.processBatch(SagaStatus.HOLDING);
  }

  @Scheduled(fixedDelay = 100)
  public void pollLocked() {
    service.processBatch(SagaStatus.LOCKED);
  }

  @Scheduled(fixedDelay = 100)
  public void pollExecuted() {
    service.processBatch(SagaStatus.EXECUTED);
  }

  @Scheduled(fixedDelay = 30_000)
  public void recoverStale() {
    service.processStale();
  }
}
