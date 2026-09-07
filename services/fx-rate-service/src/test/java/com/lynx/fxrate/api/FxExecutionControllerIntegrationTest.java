package com.lynx.fxrate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres 16 via Testcontainers, real Flyway migration — same
 * pattern as {@code LedgerControllerIntegrationTest}. No auth wiring yet
 * (see {@code FxExecutionController}'s javadoc for why), so no JWT
 * scaffolding needed here unlike ledger-service's equivalent test.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FxExecutionControllerIntegrationTest {

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
  private TestRestTemplate restTemplate;

  @Test
  void executeCreatesARecordAndGetReturnsIt() {
    UUID executionId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    Map<String, Object> body = Map.of(
        "executionId", executionId,
        "sagaId", sagaId,
        "amount", new BigDecimal("100.00"),
        "fromCurrency", "SGD",
        "toCurrency", "USD",
        "requestedRate", new BigDecimal("0.7412"));

    ResponseEntity<Map> executeResponse = restTemplate.postForEntity(
        "/v1/fx/executions", body, Map.class);
    assertThat(executeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(executeResponse.getBody().get("status")).isEqualTo("EXECUTED");

    ResponseEntity<Map> getResponse = restTemplate.exchange(
        "/v1/fx/executions/" + executionId, HttpMethod.GET, new HttpEntity<>(null), Map.class);
    assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getResponse.getBody().get("executionId")).isEqualTo(executionId.toString());
  }

  @Test
  void retryWithSameExecutionIdReturnsTheSameResult() {
    UUID executionId = UUID.randomUUID();
    UUID sagaId = UUID.randomUUID();
    Map<String, Object> body = Map.of(
        "executionId", executionId,
        "sagaId", sagaId,
        "amount", new BigDecimal("50.00"),
        "fromCurrency", "SGD",
        "toCurrency", "USD",
        "requestedRate", new BigDecimal("0.75"));

    ResponseEntity<Map> first = restTemplate.postForEntity("/v1/fx/executions", body, Map.class);
    ResponseEntity<Map> second = restTemplate.postForEntity("/v1/fx/executions", body, Map.class);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody()).isEqualTo(first.getBody());
  }

  @Test
  void getForUnknownExecutionIdReturns404() {
    ResponseEntity<Map> response = restTemplate.exchange(
        "/v1/fx/executions/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(null), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void quoteReturnsARateAndAnExpiry() {
    ResponseEntity<Map> response = restTemplate.getForEntity(
        "/v1/fx/quotes?amount=100.00&fromCurrency=SGD&toCurrency=USD", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().get("rate")).isEqualTo(0.7412);
    assertThat(response.getBody().get("expiresAt")).isNotNull();
    assertThat(response.getBody().get("quoteId")).isNotNull();
  }

  @Test
  void quoteForUnsupportedPairReturnsUnprocessable() {
    ResponseEntity<Map> response = restTemplate.getForEntity(
        "/v1/fx/quotes?amount=100.00&fromCurrency=SGD&toCurrency=JPY", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }
}
