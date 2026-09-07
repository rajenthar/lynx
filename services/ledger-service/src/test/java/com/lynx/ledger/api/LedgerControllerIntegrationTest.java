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
 * <p>No {@code Idempotency-Key} header (other-docs/08
 * Decision 29) — a retry is just calling the same phase endpoint again for
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

  @Test
  void holdCreatesTwoLedgerRowsAndAuditTrailReflectsThem() throws Exception {
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();

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
    // other-docs/08 Decision 31: a different authenticated user asking for
    // someone else's sagaId must not see its legs — an empty audit trail,
    // not user-1's real ones, even though the saga genuinely exists.
    UUID sagaId = UUID.randomUUID();
    UUID fromAccount = UUID.randomUUID();

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
}
