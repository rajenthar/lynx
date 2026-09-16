package com.lynx.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynx.security.JwtVerifier;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres 16 via Testcontainers, real Flyway migrations, real
 * SERIALIZABLE transactions hitting the real UNIQUE constraint — the
 * genuine proof java-docs/04 promised for this exact scenario.
 *
 * <p>No {@code Idempotency-Key} header — a retry is just calling the same phase endpoint again for
 * the same {@code sagaId}; {@code (userId, sagaId, phase)} is the whole
 * identity now.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "spring.main.allow-bean-definition-overriding=true")
class LedgerControllerIntegrationTest {

  private static final String ISSUER = "https://auth.lynx";
  private static final String AUDIENCE = "lynx-api";
  private static RSAKey signingKey;

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

  @BeforeAll
  static void generateSigningKey() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
  }

  @TestConfiguration
  static class TestSecurityConfig {
    @Bean
    @Primary
    public JwtVerifier jwtVerifier() {
      JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
      return new JwtVerifier(jwks, ISSUER, AUDIENCE);
    }
  }

  @Autowired
  private TestRestTemplate restTemplate;

  @org.junit.jupiter.api.BeforeEach
  void useApacheHttpClient() {
    // The JDK's default HttpURLConnection-based request factory can throw
    // HttpRetryException ("cannot retry due to server authentication, in
    // streaming mode") when POSTing a body to an endpoint that returns 401 —
    // a JDK-internal auth-retry quirk (observed non-deterministically,
    // condition-dependent), unrelated to ledger-service's own behavior.
    // Apache HttpClient doesn't have this problem, so it's used
    // unconditionally rather than relying on the JDK client and hoping the
    // triggering condition doesn't recur.
    restTemplate.getRestTemplate().setRequestFactory(
        new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
  }

  private static String token(String userId) throws Exception {
    return token(userId, List.of());
  }

  /** ADR-007 Option C: a token carrying the {@code internal-service} role. */
  private static String serviceToken(String serviceId) throws Exception {
    return token(serviceId, List.of("internal-service"));
  }

  private static String token(String subject, List<String> roles) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(subject)
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .claim("roles", roles)
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private HttpHeaders headersFor(String userId) throws Exception {
    return headersWithToken(token(userId));
  }

  private HttpHeaders headersWithToken(String bearerToken) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(bearerToken);
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return headers;
  }

  /**
   * {@code hold()} now checks the account's existing balance
   * first — every test that expects a HOLD to
   * actually succeed must fund the account first, exactly the way a real
   * caller would (via the new deposit endpoint), not just assume an
   * arbitrary fresh {@code UUID} account can be debited.
   */
  private void fundAccount(UUID accountId, String userId, BigDecimal amount, String currency) throws Exception {
    Map<String, Object> body = Map.of(
        "accountId", accountId, "amount", amount, "currencyCode", currency);
    ResponseEntity<Map> response = restTemplate.exchange(
        "/v1/ledger/deposits/" + UUID.randomUUID(),
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headersFor(userId)),
        Map.class);
    if (response.getStatusCode() != HttpStatus.OK) {
      throw new IllegalStateException("Failed to fund test account: " + response);
    }
  }

  @Test
  void holdCreatesTwoLedgerRowsAndAuditTrailReflectsThem() throws Exception {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();
    fundAccount(fromAccount, "user-1", new BigDecimal("100.00"), "SGD");

    Map<String, Object> body = Map.of(
        "fromAccountId", fromAccount,
        "amount", new BigDecimal("100.00"),
        "currencyCode", "SGD");

    ResponseEntity<Map> response = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headersFor("user-1")),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<?> legs = (List<?>) response.getBody().get("legs");
    assertThat(legs).hasSize(2);

    ResponseEntity<List> auditTrail = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId,
        org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-1")),
        List.class);
    assertThat(auditTrail.getBody()).hasSize(2);
  }

  @Test
  void retryOfTheSameSagaAndPhaseReturnsSameResultAndNoNewRows() throws Exception {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();
    fundAccount(fromAccount, "user-2", new BigDecimal("50.00"), "SGD");
    HttpHeaders headers = headersFor("user-2");

    Map<String, Object> body = Map.of(
        "fromAccountId", fromAccount,
        "amount", new BigDecimal("50.00"),
        "currencyCode", "SGD");

    ResponseEntity<Map> first = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headers),
        Map.class);
    ResponseEntity<Map> second = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headers),
        Map.class);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody()).isEqualTo(first.getBody());

    ResponseEntity<List> auditTrail = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId,
        org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headers),
        List.class);
    assertThat(auditTrail.getBody()).hasSize(2);
  }

  @Test
  void serviceCallerAssertsOnBehalfOfUserIdAndItIsHonored() throws Exception {
    // ADR-007 Option C: a caller holding an internal-service token (e.g.
    // saga-orchestrator's own ServiceTokenProvider-issued token) is trusted
    // to say who it's acting on behalf of via the request body.
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();
    fundAccount(fromAccount, "real-customer-1", new BigDecimal("100.00"), "SGD");

    Map<String, Object> body = new java.util.HashMap<>(Map.of(
        "fromAccountId", fromAccount,
        "amount", new BigDecimal("100.00"),
        "currencyCode", "SGD"));
    body.put("onBehalfOfUserId", "real-customer-1");

    ResponseEntity<Map> response = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headersWithToken(serviceToken("saga-orchestrator"))),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void serviceCallerWithoutOnBehalfOfUserIdIsRejected() throws Exception {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();

    Map<String, Object> body = Map.of(
        "fromAccountId", fromAccount,
        "amount", new BigDecimal("100.00"),
        "currencyCode", "SGD");

    ResponseEntity<Map> response = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headersWithToken(serviceToken("saga-orchestrator"))),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void ordinaryUserTokenAssertingOnBehalfOfUserIdIsForbidden() throws Exception {
    // A normal end-user token has no business asserting who it's acting for
    // — only a caller already proven to be an internal service gets that.
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();

    Map<String, Object> body = new java.util.HashMap<>(Map.of(
        "fromAccountId", fromAccount,
        "amount", new BigDecimal("100.00"),
        "currencyCode", "SGD"));
    body.put("onBehalfOfUserId", "someone-else");

    ResponseEntity<Map> response = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headersFor("user-1")),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void missingBearerTokenIsRejected() {
    UUID sagaId = UUID.randomUUID();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

    ResponseEntity<Map> response = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(Map.of(), headers),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void auditTrailIsScopedToTheCallingUserNotJustTheSagaId() throws Exception {
    // A different authenticated user asking for
    // someone else's sagaId must not see its legs — an empty audit trail,
    // not user-1's real ones, even though the saga genuinely exists.
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();
    fundAccount(fromAccount, "user-1", new BigDecimal("100.00"), "SGD");

    Map<String, Object> body = Map.of(
        "fromAccountId", fromAccount,
        "amount", new BigDecimal("100.00"),
        "currencyCode", "SGD");
    restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(body, headersFor("user-1")),
        Map.class);

    ResponseEntity<List> auditTrailAsOwner = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId,
        org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-1")),
        List.class);
    assertThat(auditTrailAsOwner.getBody()).hasSize(2);

    ResponseEntity<List> auditTrailAsStranger = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId,
        org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-2")),
        List.class);
    assertThat(auditTrailAsStranger.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(auditTrailAsStranger.getBody()).isEmpty();
  }

  @Test
  void lockedRateIsScopedToTheCallingUserNotJustTheSagaId() throws Exception {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();

    Map<String, Object> holdBody = Map.of(
        "fromAccountId", fromAccount,
        "amount", new BigDecimal("100.00"),
        "currencyCode", "SGD");
    restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(holdBody, headersFor("user-1")),
        Map.class);

    Map<String, Object> lockBody = new java.util.HashMap<>();
    lockBody.put("lockedAmount", new BigDecimal("100.00"));
    lockBody.put("currencyCode", "SGD");
    lockBody.put("fromCurrency", "SGD");
    lockBody.put("toCurrency", "USD");
    lockBody.put("rate", new BigDecimal("0.74"));
    lockBody.put("rateExpiresAt", Instant.now().plusSeconds(60).toString());
    restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/lock",
        org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(lockBody, headersFor("user-1")),
        Map.class);

    ResponseEntity<Map> rateAsOwner = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/rate",
        org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-1")),
        Map.class);
    assertThat(rateAsOwner.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> rateAsStranger = restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/rate",
        org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-2")),
        Map.class);
    assertThat(rateAsStranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /**
   * The actual proof for the race raised in review: at the default
   * READ_COMMITTED isolation, two concurrent {@code hold()} calls on the
   * SAME account could each read a balance that individually looks
   * sufficient, both pass the check, and both debit — overdrawing the
   * account despite each check being individually correct. Funds exactly
   * one hold's worth, fires two hold requests at the same account
   * simultaneously (a {@link java.util.concurrent.CyclicBarrier} to
   * maximize actual overlap, not just "started close together"), and
   * asserts EXACTLY one succeeds — never both, never a silent overdraw.
   */
  @Test
  void concurrentHoldsOnTheSameAccountNeverBothSucceed() throws Exception {
    UUID account = UUID.randomUUID();
    fundAccount(account, "user-1", new BigDecimal("100.00"), "SGD");

    Map<String, Object> body = Map.of(
        "fromAccountId", account, "amount", new BigDecimal("100.00"), "currencyCode", "SGD");
    HttpHeaders headers = headersFor("user-1");
    java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);

    java.util.concurrent.Callable<ResponseEntity<Map>> holdAttempt = () -> {
      barrier.await(); // both threads block here until both are ready, then release together
      return restTemplate.exchange(
          "/v1/ledger/sagas/" + UUID.randomUUID() + "/hold",
          org.springframework.http.HttpMethod.POST,
          new HttpEntity<>(body, headers),
          Map.class);
    };

    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Future<ResponseEntity<Map>> first = pool.submit(holdAttempt);
      java.util.concurrent.Future<ResponseEntity<Map>> second = pool.submit(holdAttempt);
      HttpStatus statusA = (HttpStatus) first.get().getStatusCode();
      HttpStatus statusB = (HttpStatus) second.get().getStatusCode();

      long successCount = List.of(statusA, statusB).stream().filter(s -> s == HttpStatus.OK).count();
      assertThat(successCount).as("exactly one of the two concurrent holds should succeed").isEqualTo(1);
      // The loser normally gets a clean 422 insufficient-funds rejection —
      // the guarded UPDATE's row lock makes it wait for the winner to
      // commit, then re-evaluate its own WHERE clause against the
      // now-debited balance. A 503 is still
      // allowed here defensively (a genuine multi-row deadlock is possible
      // in general, just not expected in this specific single-row
      // scenario) — either outcome is correct, both mean nothing was
      // silently overdrawn.
      HttpStatus loserStatus = statusA == HttpStatus.OK ? statusB : statusA;
      assertThat(loserStatus).isIn(HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.SERVICE_UNAVAILABLE);
    } finally {
      pool.shutdown();
    }
  }

  /**
   * Proves the lock-ordering fix, not just reasons about it.
   *
   * <p>{@code HOLD} on account A locks {@code [A, HOLD_POOL]} in that
   * order; {@code RELEASE} back into account A locks {@code [HOLD_POOL,
   * A]} — the REVERSE order — since a release's legs are constructed as
   * {@code [sourcePool, accountId]}. Two different, entirely unrelated
   * sagas doing exactly that at the same instant is the textbook
   * deadlock precondition: each holds one of the two shared rows and
   * waits for the other. Run repeatedly (deadlocks are timing-dependent,
   * not everywhere-or-nowhere) to give a lingering ordering bug a real
   * chance to surface as a flaky 503, rather than proving nothing by
   * relying on a single lucky interleaving.
   */
  @Test
  void aHoldAndAReleaseRacingOnTheSameAccountNeverDeadlock() throws Exception {
    UUID account = UUID.randomUUID();
    fundAccount(account, "user-1", new BigDecimal("10000.00"), "SGD");
    HttpHeaders headers = headersFor("user-1");

    for (int i = 0; i < 20; i++) {
      // An unrelated saga, already HELD elsewhere, now being compensated
      // back into the SAME account the concurrent fresh HOLD below will
      // also touch — the only way both legs can land on the same two
      // account_balances rows (the account, and the shared HOLD_POOL row).
      UUID priorSaga = UUID.randomUUID();
      Map<String, Object> priorHoldBody = Map.of(
          "fromAccountId", account, "amount", new BigDecimal("1.00"), "currencyCode", "SGD");
      restTemplate.exchange("/v1/ledger/sagas/" + priorSaga + "/hold",
          org.springframework.http.HttpMethod.POST, new HttpEntity<>(priorHoldBody, headers), Map.class);

      Map<String, Object> releaseBody = Map.of(
          "accountId", account, "amount", new BigDecimal("1.00"), "currencyCode", "SGD",
          "reason", "test compensation");
      Map<String, Object> freshHoldBody = Map.of(
          "fromAccountId", account, "amount", new BigDecimal("1.00"), "currencyCode", "SGD");

      java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
      java.util.concurrent.Callable<ResponseEntity<Map>> releaseAttempt = () -> {
        barrier.await();
        return restTemplate.exchange("/v1/ledger/sagas/" + priorSaga + "/release",
            org.springframework.http.HttpMethod.POST, new HttpEntity<>(releaseBody, headers), Map.class);
      };
      java.util.concurrent.Callable<ResponseEntity<Map>> freshHoldAttempt = () -> {
        barrier.await();
        return restTemplate.exchange("/v1/ledger/sagas/" + UUID.randomUUID() + "/hold",
            org.springframework.http.HttpMethod.POST, new HttpEntity<>(freshHoldBody, headers), Map.class);
      };

      java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        java.util.concurrent.Future<ResponseEntity<Map>> releaseFuture = pool.submit(releaseAttempt);
        java.util.concurrent.Future<ResponseEntity<Map>> holdFuture = pool.submit(freshHoldAttempt);
        HttpStatus releaseStatus = (HttpStatus) releaseFuture.get().getStatusCode();
        HttpStatus holdStatus = (HttpStatus) holdFuture.get().getStatusCode();

        // Lock ordering makes the deadlock IMPOSSIBLE, not just unlikely —
        // a 503 here would mean the fix regressed, not just bad luck.
        assertThat(releaseStatus).as("iteration %d: release should never deadlock", i).isEqualTo(HttpStatus.OK);
        assertThat(holdStatus).as("iteration %d: hold should never deadlock", i).isEqualTo(HttpStatus.OK);
      } finally {
        pool.shutdown();
      }
    }
  }

  @Test
  void accountHistoryReturnsEveryLegForThatAccountNewestFirst() throws Exception {
    UUID account = UUID.randomUUID();
    fundAccount(account, "user-1", new BigDecimal("100.00"), "SGD"); // DEPOSIT_CR

    UUID sagaId = UUID.randomUUID();
    Map<String, Object> holdBody = Map.of(
        "fromAccountId", account, "amount", new BigDecimal("40.00"), "currencyCode", "SGD");
    restTemplate.exchange(
        "/v1/ledger/sagas/" + sagaId + "/hold", org.springframework.http.HttpMethod.POST,
        new HttpEntity<>(holdBody, headersFor("user-1")), Map.class); // HOLD_DR

    ResponseEntity<List> response = restTemplate.exchange(
        "/v1/ledger/accounts/" + account + "/history", org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-1")), List.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).hasSize(2);
    // Newest first — HOLD_DR (just written) before the earlier DEPOSIT_CR.
    assertThat(((Map<?, ?>) response.getBody().get(0)).get("entryType")).isEqualTo("HOLD_DR");
    assertThat(((Map<?, ?>) response.getBody().get(1)).get("entryType")).isEqualTo("DEPOSIT_CR");
  }

  @Test
  void accountHistoryRespectsALimitParameter() throws Exception {
    UUID account = UUID.randomUUID();
    fundAccount(account, "user-1", new BigDecimal("10.00"), "SGD");
    fundAccount(account, "user-1", new BigDecimal("10.00"), "SGD");
    fundAccount(account, "user-1", new BigDecimal("10.00"), "SGD");

    ResponseEntity<List> response = restTemplate.exchange(
        "/v1/ledger/accounts/" + account + "/history?limit=1", org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-1")), List.class);

    assertThat(response.getBody()).hasSize(1);
  }
}
