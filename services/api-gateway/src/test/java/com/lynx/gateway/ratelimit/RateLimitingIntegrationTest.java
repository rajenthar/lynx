package com.lynx.gateway.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The actual token-bucket behavior against a REAL Redis
 * (Testcontainers, same pattern {@code RedisIdempotencyCacheTest} already
 * uses elsewhere in this project) — not simulated. A tight
 * {@code burstCapacity} is configured for THIS test class specifically,
 * so the bucket can be genuinely exhausted within a handful of requests
 * rather than needing hundreds.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "10000")
class RateLimitingIntegrationTest {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static final int ACCOUNT_PORT = 18184;
  private static WireMockServer accountService;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

    registry.add("spring.cloud.gateway.routes[0].id", () -> "account-service");
    registry.add("spring.cloud.gateway.routes[0].uri", () -> "http://localhost:" + ACCOUNT_PORT);
    registry.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/v1/accounts/**");
    // No auth-service/transaction-service routes needed for this test —
    // exactly one route is enough to exercise the shared default-filters.

    // Fully self-contained (not just overriding two leaf keys of
    // application.yml's own definition) — indexed-list properties don't
    // reliably merge partial overrides across property sources, so this
    // whole filter definition is restated here. A DELIBERATELY tight
    // bucket: refill 1/sec, hold at most 3 — small enough to exhaust
    // within a handful of requests in one test method, unlike the real
    // 10/20 configured for production in application.yml.
    registry.add("spring.cloud.gateway.default-filters[0].name", () -> "RequestRateLimiter");
    registry.add("spring.cloud.gateway.default-filters[0].args.redis-rate-limiter.replenishRate", () -> "1");
    registry.add("spring.cloud.gateway.default-filters[0].args.redis-rate-limiter.burstCapacity", () -> "3");
    registry.add("spring.cloud.gateway.default-filters[0].args.redis-rate-limiter.requestedTokens", () -> "1");
    registry.add("spring.cloud.gateway.default-filters[0].args.key-resolver", () -> "#{@rateLimitKeyResolver}");
  }

  @BeforeAll
  static void startStub() {
    accountService = new WireMockServer(WireMockConfiguration.options().port(ACCOUNT_PORT));
    accountService.start();
    accountService.stubFor(get(urlEqualTo("/v1/accounts"))
        .willReturn(aResponse().withStatus(200).withBody("[]")
            .withHeader("Content-Type", "application/json")));
  }

  @AfterAll
  static void stopStub() {
    accountService.stop();
  }

  @Autowired
  private WebTestClient client;

  @Test
  void aBurstOfRequestsFromTheSameUserIsThrottledOnceTheBucketIsExhausted() {
    String bearerForOneUser = "Bearer " + fakeJwt("user-burst-test");

    List<HttpStatus> statuses = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      HttpStatus status = (HttpStatus) client.get().uri("/v1/accounts")
          .header("Authorization", bearerForOneUser)
          .exchange()
          .returnResult(String.class)
          .getStatus();
      statuses.add(status);
    }

    // burstCapacity=3: the first 3 requests (within the same instant,
    // before any replenishment) succeed; requests 4 and 5 exhaust the
    // bucket and get throttled. Exact counts can shift by one depending
    // on timing/replenishment between calls, so assert the SHAPE of the
    // result — at least one 200, at least one 429 — rather than a
    // precise position.
    assertThat(statuses).contains(HttpStatus.OK);
    assertThat(statuses).contains(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void twoDifferentUsersGetIndependentBuckets() {
    String userA = "Bearer " + fakeJwt("user-a");
    String userB = "Bearer " + fakeJwt("user-b");

    // Exhaust user A's bucket completely.
    for (int i = 0; i < 4; i++) {
      client.get().uri("/v1/accounts").header("Authorization", userA).exchange();
    }

    // user B, never called before, still gets a fresh bucket — proving
    // the KeyResolver genuinely separates callers, not one shared bucket
    // for the whole route.
    client.get().uri("/v1/accounts")
        .header("Authorization", userB)
        .exchange()
        .expectStatus().isOk();
  }

  private static String fakeJwt(String sub) {
    String header = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"alg\":\"RS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String payload = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return header + "." + payload + ".fake-signature";
  }
}
