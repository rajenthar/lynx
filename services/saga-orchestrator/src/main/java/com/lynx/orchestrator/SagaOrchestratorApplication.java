package com.lynx.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} activates {@link
 * com.lynx.orchestrator.scheduler.SagaPollingScheduler}'s {@code @Scheduled}
 * methods — the four-phase polling loop and the recovery worker (ADR-003).
 */
@SpringBootApplication
@EnableScheduling
public class SagaOrchestratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(SagaOrchestratorApplication.class, args);
  }
}
