package com.lynx.account.config;

import com.lynx.account.client.LedgerServiceClient;
import com.lynx.account.projection.EventProjector;
import com.lynx.account.projection.ProcessedEventMarkingRecoverer;
import com.lynx.account.repository.AccountRepository;
import com.lynx.account.repository.ProcessedEventRepository;
import com.lynx.account.service.AccountService;
import com.lynx.security.JwtVerifier;
import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.RestClient;

@Configuration
public class BeansConfig {

  /** Same shape as saga-orchestrator's downstream-call breakers (other-docs/10 Decision 8) — one instance, this service's only downstream call. */
  private static final CircuitBreakerConfig LEDGER_CIRCUIT_BREAKER_CONFIG = CircuitBreakerConfig.custom()
      .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
      .slidingWindowSize(5)
      .minimumNumberOfCalls(5)
      .failureRateThreshold(100)
      .waitDurationInOpenState(Duration.ofSeconds(30))
      .permittedNumberOfCallsInHalfOpenState(1)
      .ignoreExceptions(org.springframework.web.client.HttpClientErrorException.class)
      .build();

  @Bean
  public JwtVerifier jwtVerifier(
      @Value("${lynx.security.jwks-url}") String jwksUrl,
      @Value("${lynx.security.issuer}") String issuer,
      @Value("${lynx.security.audience}") String audience) {
    return JwtVerifier.fromJwksUrl(jwksUrl, issuer, audience);
  }

  @Bean
  public ServiceTokenProvider serviceTokenProvider(
      @Value("${lynx.service-token.token-endpoint}") String tokenEndpoint,
      @Value("${lynx.service-token.client-id}") String clientId,
      @Value("${lynx.service-token.client-secret}") String clientSecret) {
    return new ServiceTokenProvider(HttpClient.newHttpClient(), URI.create(tokenEndpoint), clientId, clientSecret);
  }

  @Bean
  public LedgerServiceClient ledgerServiceClient(
      @Value("${lynx.clients.ledger-service.base-url}") String baseUrl,
      ServiceTokenProvider serviceTokenProvider) {
    CircuitBreaker circuitBreaker = CircuitBreaker.of("ledger-service", LEDGER_CIRCUIT_BREAKER_CONFIG);
    return new LedgerServiceClient(
        RestClient.builder().baseUrl(baseUrl).build(), serviceTokenProvider, circuitBreaker);
  }

  @Bean
  public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
    return new JpaTransactionManager(emf);
  }

  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }

  @Bean
  public EventProjector eventProjector(AccountRepository accountRepository,
                                        ProcessedEventRepository processedEventRepository,
                                        TransactionTemplate transactionTemplate) {
    return new EventProjector(accountRepository, processedEventRepository, transactionTemplate);
  }

  @Bean
  public AccountService accountService(AccountRepository accountRepository,
                                        LedgerServiceClient ledgerServiceClient) {
    return new AccountService(accountRepository, ledgerServiceClient);
  }

  /**
   * "Cannot miss a message" (other-docs/12 Decision 8) — {@link
   * com.lynx.account.projection.OutboxEventConsumer#onMessage} no longer
   * catches/swallows failures itself; this bean is what actually handles
   * them. Spring Boot auto-detects a single {@code CommonErrorHandler}
   * bean and applies it to the auto-configured listener container
   * factory, so no other wiring is needed.
   *
   * <p>{@link FixedBackOff}: 3 retries, 1 second apart — enough for a
   * genuinely transient failure (e.g. Postgres briefly unreachable
   * mid-{@code apply()}) to succeed on redelivery without a human needing
   * to look at it. Only once retries are exhausted does the {@link
   * DeadLetterPublishingRecoverer} publish the RAW original message to
   * {@code <topic>.DLT} (same partition number) — preserved for
   * investigation, never silently dropped, which the previous
   * catch-log-and-ack version did.
   *
   * <p>Wrapped in {@link ProcessedEventMarkingRecoverer} — see its own
   * javadoc for why a dead-lettered event ALSO needs a {@code
   * processed_events} row, exactly like a successfully-applied one.
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations,
                                                ProcessedEventRepository processedEventRepository) {
    DeadLetterPublishingRecoverer deadLetterRecoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
        (ConsumerRecord<?, ?> record, Exception ex) ->
            new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition()));
    ProcessedEventMarkingRecoverer recoverer =
        new ProcessedEventMarkingRecoverer(deadLetterRecoverer, processedEventRepository);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
  }
}
