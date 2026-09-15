package com.lynx.account.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynx.account.domain.Account;
import com.lynx.account.repository.AccountRepository;
import com.lynx.account.repository.ProcessedEventRepository;
import com.lynx.events.EventEnvelope;
import com.lynx.events.FundsDeposited;
import com.lynx.events.MoneyAmount;
import com.lynx.events.RateLocked;
import com.lynx.events.TransferFailed;
import com.lynx.events.TransferHeld;
import com.lynx.events.TransferSettled;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres via Testcontainers — the actually novel logic (decode →
 * apply → idempotent record via {@code processed_events}) this whole
 * projection depends on, proven directly against {@link
 * EventProjector#apply}, not through a real Kafka broker (see
 * other-docs/12's test-boundary decision, and {@link
 * OutboxEventConsumer}'s own javadoc).
 */
@Testcontainers
@SpringBootTest
class EventProjectorIntegrationTest {

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
    // No real Kafka broker in this test run — OutboxEventConsumer would
    // fail to start a listener container without one. autoStartup=false
    // keeps the Spring context bootable while testing EventProjector directly.
    registry.add("spring.kafka.listener.auto-startup", () -> "false");
  }

  @Autowired
  private AccountRepository accountRepository;
  @Autowired
  private ProcessedEventRepository processedEventRepository;
  @Autowired
  private EventProjector eventProjector;

  // processed_events is a table SHARED across every test method in this
  // class (same Postgres container, nothing truncates between tests) — a
  // literal eventId reused across two different test methods would look
  // like a redelivery of an event another test already recorded, and get
  // silently skipped. Each test claims its own never-before-used id.
  private static final java.util.concurrent.atomic.AtomicLong NEXT_EVENT_ID =
      new java.util.concurrent.atomic.AtomicLong(1);

  private UUID accountId;
  private UUID otherAccountId;

  @BeforeEach
  void setUp() {
    accountId = UUID.randomUUID();
    otherAccountId = UUID.randomUUID();
    // Fresh, per-test userId — other-docs/12's later UNIQUE(user_id,
    // currency) constraint means a fixed "user-1"/"SGD" pair would only
    // ever insert successfully in the FIRST test method to run in this
    // class (same Postgres container reused across all @Test methods,
    // nothing truncates between them).
    accountRepository.save(new Account(accountId, "user-" + UUID.randomUUID(), "SGD", Instant.now()));
    accountRepository.save(new Account(otherAccountId, "user-" + UUID.randomUUID(), "USD", Instant.now()));
  }

  @Test
  void transferHeldDebitsAvailableAndCreditsHeld() {
    eventProjector.apply(EventEnvelope.of(NEXT_EVENT_ID.getAndIncrement(), UUID.randomUUID(), "corr-1",
        new TransferHeld(accountId, new MoneyAmount(10000, "SGD"))));

    Account account = accountRepository.findById(accountId).orElseThrow();
    assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("-100.00"));
    assertThat(account.getHeld()).isEqualByComparingTo(new BigDecimal("100.00"));
  }

  @Test
  void fundsDepositedCreditsAvailableOnly() {
    eventProjector.apply(EventEnvelope.of(NEXT_EVENT_ID.getAndIncrement(), UUID.randomUUID(), "corr-1",
        new FundsDeposited(accountId, new MoneyAmount(50000, "SGD"))));

    Account account = accountRepository.findById(accountId).orElseThrow();
    assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("500.00"));
    assertThat(account.getHeld()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void transferSettledMovesHeldOnSourceAndAvailableOnRecipient() {
    eventProjector.apply(EventEnvelope.of(NEXT_EVENT_ID.getAndIncrement(), UUID.randomUUID(), "corr-1",
        new TransferHeld(accountId, new MoneyAmount(10000, "SGD"))));

    eventProjector.apply(EventEnvelope.of(NEXT_EVENT_ID.getAndIncrement(), UUID.randomUUID(), "corr-1",
        new TransferSettled(accountId, otherAccountId,
            new MoneyAmount(10000, "SGD"), new MoneyAmount(6853, "USD"))));

    Account source = accountRepository.findById(accountId).orElseThrow();
    Account recipient = accountRepository.findById(otherAccountId).orElseThrow();
    assertThat(source.getHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(source.getAvailable()).isEqualByComparingTo(new BigDecimal("-100.00"));
    assertThat(recipient.getAvailable()).isEqualByComparingTo(new BigDecimal("68.53"));
  }

  @Test
  void transferFailedReleasesHeldBackToAvailable() {
    eventProjector.apply(EventEnvelope.of(NEXT_EVENT_ID.getAndIncrement(), UUID.randomUUID(), "corr-1",
        new TransferHeld(accountId, new MoneyAmount(10000, "SGD"))));

    eventProjector.apply(EventEnvelope.of(NEXT_EVENT_ID.getAndIncrement(), UUID.randomUUID(), "corr-1",
        new TransferFailed(accountId, new MoneyAmount(10000, "SGD"), "insufficient funds downstream")));

    Account account = accountRepository.findById(accountId).orElseThrow();
    assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getHeld()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void rateLockedIsIgnoredButIsStillRecordedAsProcessed() {
    long eventId = NEXT_EVENT_ID.getAndIncrement();
    eventProjector.apply(EventEnvelope.of(eventId, UUID.randomUUID(), "corr-1",
        new RateLocked("SGD", "USD", new BigDecimal("0.74"))));

    Account account = accountRepository.findById(accountId).orElseThrow();
    assertThat(account.getAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(processedEventRepository.existsById(eventId)).isTrue();
  }

  @Test
  void redeliveryOfAnAlreadyAppliedEventIdIsANoOp() {
    long eventId = NEXT_EVENT_ID.getAndIncrement();
    eventProjector.apply(EventEnvelope.of(eventId, UUID.randomUUID(), "corr-1",
        new FundsDeposited(accountId, new MoneyAmount(50000, "SGD"))));
    // Same eventId, redelivered — simulates Kafka's at-least-once guarantee.
    eventProjector.apply(EventEnvelope.of(eventId, UUID.randomUUID(), "corr-1",
        new FundsDeposited(accountId, new MoneyAmount(50000, "SGD"))));

    Account account = accountRepository.findById(accountId).orElseThrow();
    assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("500.00")); // NOT 1000.00
  }

  @Test
  void anEventForAnUnknownAccountIsSkippedNotThrown() {
    long eventId = NEXT_EVENT_ID.getAndIncrement();
    eventProjector.apply(EventEnvelope.of(eventId, UUID.randomUUID(), "corr-1",
        new FundsDeposited(UUID.randomUUID(), new MoneyAmount(50000, "SGD"))));

    assertThat(processedEventRepository.existsById(eventId)).isTrue();
  }

  /**
   * The actual property other-docs/12 Decision 7 fixes: a Kafka topic with
   * multiple partitions gives NO guarantee that a lower eventId is
   * delivered before a higher one — only within one partition. A
   * high-water-mark ("skip if eventId &lt;= last seen") would have WRONGLY
   * dropped the lower-numbered event here, treating "arrived out of order"
   * as if it were "already applied." The per-event {@code processed_events}
   * check has no such assumption — applies correctly regardless of the
   * order these two calls happen in.
   */
  @Test
  void aLowerEventIdArrivingAfterAHigherOneIsStillApplied_notWronglySkipped() {
    long higherEventId = NEXT_EVENT_ID.getAndIncrement();
    long lowerEventId = NEXT_EVENT_ID.getAndIncrement(); // allocated second, but "delivered" first below

    // The higher id is applied FIRST (as if it arrived via a faster partition)...
    eventProjector.apply(EventEnvelope.of(higherEventId, UUID.randomUUID(), "corr-1",
        new FundsDeposited(accountId, new MoneyAmount(30000, "SGD"))));
    // ...then the LOWER id arrives after it. A high-water-mark design would
    // see lowerEventId <= higherEventId (already "seen") and wrongly skip this.
    eventProjector.apply(EventEnvelope.of(lowerEventId, UUID.randomUUID(), "corr-1",
        new FundsDeposited(accountId, new MoneyAmount(20000, "SGD"))));

    Account account = accountRepository.findById(accountId).orElseThrow();
    // Both deposits applied — 300.00 + 200.00, regardless of delivery order.
    assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("500.00"));
    assertThat(processedEventRepository.existsById(lowerEventId)).isTrue();
  }
}
