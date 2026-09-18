package com.lynx.transaction.config;

import com.lynx.security.JwtVerifier;
import com.lynx.security.ServiceTokenProvider;
import com.lynx.transaction.client.AccountServiceClient;
import com.lynx.transaction.client.SagaOrchestratorClient;
import com.lynx.transaction.service.TransactionService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Same shape every other internal caller's {@code BeansConfig} already
 * uses — one {@link ServiceTokenProvider}
 * shared by both downstream clients (a single service identity
 * authenticates this service to every other one identically), each client
 * with its OWN named {@link CircuitBreaker} instance so a struggling
 * {@code saga-orchestrator} can't affect calls to {@code account-service}
 * or vice versa.
 */
@Configuration
public class BeansConfig {

  private static final CircuitBreakerConfig DOWNSTREAM_CALL_CIRCUIT_BREAKER_CONFIG = CircuitBreakerConfig.custom()
      .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
      .slidingWindowSize(5)
      .minimumNumberOfCalls(5)
      .failureRateThreshold(100)
      .waitDurationInOpenState(Duration.ofSeconds(30))
      .permittedNumberOfCallsInHalfOpenState(1)
      .ignoreExceptions(HttpClientErrorException.class)
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
  public SagaOrchestratorClient sagaOrchestratorClient(
      RestClient.Builder restClientBuilder,
      @Value("${lynx.clients.saga-orchestrator.base-url}") String baseUrl,
      ServiceTokenProvider serviceTokenProvider) {
    CircuitBreaker circuitBreaker = CircuitBreaker.of("saga-orchestrator", DOWNSTREAM_CALL_CIRCUIT_BREAKER_CONFIG);
    // The INJECTED builder, not RestClient.builder() called directly — see
    // docs/html/metrics_traces_logs/trace-propagation.html for why this is
    // what makes cross-service trace propagation actually work.
    return new SagaOrchestratorClient(
        restClientBuilder.baseUrl(baseUrl).build(), serviceTokenProvider, circuitBreaker);
  }

  @Bean
  public AccountServiceClient accountServiceClient(
      RestClient.Builder restClientBuilder,
      @Value("${lynx.clients.account-service.base-url}") String baseUrl,
      ServiceTokenProvider serviceTokenProvider) {
    CircuitBreaker circuitBreaker = CircuitBreaker.of("account-service", DOWNSTREAM_CALL_CIRCUIT_BREAKER_CONFIG);
    return new AccountServiceClient(
        restClientBuilder.baseUrl(baseUrl).build(), serviceTokenProvider, circuitBreaker);
  }

  @Bean
  public TransactionService transactionService(SagaOrchestratorClient sagaOrchestratorClient,
                                                AccountServiceClient accountServiceClient) {
    return new TransactionService(sagaOrchestratorClient, accountServiceClient);
  }
}
