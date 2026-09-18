package com.lynx.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real routing, against real WireMock stubs standing in for {@code
 * auth-service}/{@code account-service}/{@code transaction-service} (no
 * real downstream services running) — proving each of the three real
 * routes forwards correctly, AND that {@code
 * /internal/accounts/resolve-transfer} has no matching route at all
 * A real Redis (Testcontainers) is still needed here —
 * {@code RequestRateLimiter} is a default filter on every route, so even
 * a routing-only test exercises it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "10000")
class RoutingIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static WireMockServer authService;
  private static WireMockServer accountService;
  private static WireMockServer transactionService;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    // Route each id's own `uri` at the WireMock instance standing in for
    // that real service, instead of the real fixed ports in application.yml.
    registry.add("spring.cloud.gateway.routes[0].id", () -> "auth-service-register");
    registry.add("spring.cloud.gateway.routes[0].uri", () -> "http://localhost:" + AUTH_PORT);
    registry.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/auth/register");
    registry.add("spring.cloud.gateway.routes[0].predicates[1]", () -> "Method=POST");

    registry.add("spring.cloud.gateway.routes[1].id", () -> "auth-service-login");
    registry.add("spring.cloud.gateway.routes[1].uri", () -> "http://localhost:" + AUTH_PORT);
    registry.add("spring.cloud.gateway.routes[1].predicates[0]", () -> "Path=/auth/login");
    registry.add("spring.cloud.gateway.routes[1].predicates[1]", () -> "Method=POST");

    registry.add("spring.cloud.gateway.routes[2].id", () -> "account-service");
    registry.add("spring.cloud.gateway.routes[2].uri", () -> "http://localhost:" + ACCOUNT_PORT);
    registry.add("spring.cloud.gateway.routes[2].predicates[0]", () -> "Path=/v1/accounts/**");

    registry.add("spring.cloud.gateway.routes[3].id", () -> "transaction-service");
    registry.add("spring.cloud.gateway.routes[3].uri", () -> "http://localhost:" + TRANSACTION_PORT);
    registry.add("spring.cloud.gateway.routes[3].predicates[0]", () -> "Path=/v1/transfers/**");

    // Fully self-contained (not just overriding two leaf keys of
    // application.yml's own definition) — indexed-list properties don't
    // reliably merge partial overrides across property sources, so this
    // whole filter definition is restated here. A generous rate limit
    // for THIS test class specifically — its own point is routing, not
    // rate limiting (that's RateLimitingIntegrationTest's job).
    registry.add("spring.cloud.gateway.default-filters[0].name", () -> "RequestRateLimiter");
    registry.add("spring.cloud.gateway.default-filters[0].args.redis-rate-limiter.replenishRate", () -> "1000");
    registry.add("spring.cloud.gateway.default-filters[0].args.redis-rate-limiter.burstCapacity", () -> "1000");
    registry.add("spring.cloud.gateway.default-filters[0].args.redis-rate-limiter.requestedTokens", () -> "1");
    registry.add("spring.cloud.gateway.default-filters[0].args.key-resolver", () -> "#{@rateLimitKeyResolver}");
  }

  private static final int AUTH_PORT = 18080;
  private static final int ACCOUNT_PORT = 18084;
  private static final int TRANSACTION_PORT = 18085;

  @BeforeAll
  static void startStubs() {
    authService = new WireMockServer(WireMockConfiguration.options().port(AUTH_PORT));
    accountService = new WireMockServer(WireMockConfiguration.options().port(ACCOUNT_PORT));
    transactionService = new WireMockServer(WireMockConfiguration.options().port(TRANSACTION_PORT));
    authService.start();
    accountService.start();
    transactionService.start();
  }

  @AfterAll
  static void stopStubs() {
    authService.stop();
    accountService.stop();
    transactionService.stop();
  }

  @BeforeEach
  void resetStubs() {
    authService.resetAll();
    accountService.resetAll();
    transactionService.resetAll();
  }

  @org.springframework.beans.factory.annotation.Autowired
  private WebTestClient client;

  @Test
  void routesRegisterToAuthService() {
    authService.stubFor(post(urlEqualTo("/auth/register"))
        .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")
            .withHeader("Content-Type", "application/json")));

    client.post().uri("/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"email\":\"a@b.com\",\"password\":\"x\"}")
        .exchange()
        .expectStatus().isOk();
  }

  @Test
  void routesListAccountsToAccountService() {
    accountService.stubFor(get(urlEqualTo("/v1/accounts"))
        .willReturn(aResponse().withStatus(200).withBody("[]")
            .withHeader("Content-Type", "application/json")));

    client.get().uri("/v1/accounts")
        .header("Authorization", "Bearer irrelevant-for-this-test")
        .exchange()
        .expectStatus().isOk();
  }

  @Test
  void routesTransfersToTransactionService() {
    transactionService.stubFor(post(urlEqualTo("/v1/transfers"))
        .willReturn(aResponse().withStatus(202).withBody("{\"sagaId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"HOLDING\"}")
            .withHeader("Content-Type", "application/json")));

    client.post().uri("/v1/transfers")
        .header("Idempotency-Key", "22222222-2222-2222-2222-222222222222")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"recipientUserId\":\"u2\",\"fromCurrency\":\"SGD\",\"toCurrency\":\"SGD\",\"amount\":10.00}")
        .exchange()
        .expectStatus().isEqualTo(202);
  }

  @Test
  void internalAccountResolveEndpointHasNoRouteAtAll() {
    // No stub needed — the point is the gateway never even reaches out;
    // Spring Cloud Gateway responds 404 itself when nothing matches.
    client.post().uri("/internal/accounts/resolve-transfer")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus().isNotFound();

    accountService.verify(0, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
        urlEqualTo("/internal/accounts/resolve-transfer")));
  }
}
