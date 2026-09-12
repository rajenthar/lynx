package com.lynx.orchestrator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.lynx.orchestrator.client.FxRateServiceClient;
import com.lynx.orchestrator.client.LedgerServiceClient;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres via Testcontainers, real Flyway migration, real signed JWTs
 * — same shape as {@code LedgerControllerIntegrationTest}. {@link
 * LedgerServiceClient}/{@link FxRateServiceClient} are {@code @MockBean}s:
 * this test proves {@code SagaController}'s own auth trust boundary and
 * {@code saga_state} persistence, not a real HTTP round trip to
 * ledger-service/fx-rate-service (those get their own real proof in their
 * own test suites; wiring three real services together end to end is a
 * later, separate verification step — see other-docs/10's Deferred list).
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "lynx.scheduler.enabled=false"
    })
class SagaControllerIntegrationTest {

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

  @MockBean
  private LedgerServiceClient ledgerServiceClient;

  @MockBean
  private FxRateServiceClient fxRateServiceClient;

  @Autowired
  private TestRestTemplate restTemplate;

  @org.junit.jupiter.api.BeforeEach
  void useApacheHttpClient() {
    // Same JDK HttpRetryException-on-401 quirk noted in
    // LedgerControllerIntegrationTest — Apache HttpClient sidesteps it.
    restTemplate.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory());
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

  private static String serviceToken() throws Exception {
    return token("saga-orchestrator-caller", List.of("internal-service"));
  }

  private HttpHeaders headersWithToken(String bearerToken) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(bearerToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private static Map<String, Object> createBody(UUID sagaId, String userId) {
    Map<String, Object> body = new HashMap<>();
    body.put("sagaId", sagaId);
    body.put("fromAccountId", UUID.randomUUID());
    body.put("toAccountId", UUID.randomUUID());
    body.put("amount", new BigDecimal("100.00"));
    body.put("fromCurrency", "SGD");
    body.put("toCurrency", "USD");
    body.put("retriedFromSagaId", null);
    body.put("onBehalfOfUserId", userId);
    return body;
  }

  @Test
  void internalServiceCallerCreatesASagaAndItStartsHolding() throws Exception {
    UUID sagaId = UUID.randomUUID();

    ResponseEntity<Map> response = restTemplate.exchange(
        "/internal/sagas",
        HttpMethod.POST,
        new HttpEntity<>(createBody(sagaId, "user-1"), headersWithToken(serviceToken())),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().get("status")).isEqualTo("HOLDING");
    verify(ledgerServiceClient).hold(any(), anyString(), any(), any(), anyString());

    ResponseEntity<Map> getResponse = restTemplate.exchange(
        "/internal/sagas/" + sagaId + "?onBehalfOfUserId=user-1",
        HttpMethod.GET,
        new HttpEntity<>(null, headersWithToken(serviceToken())),
        Map.class);
    assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getResponse.getBody().get("status")).isEqualTo("HOLDING");
  }

  @Test
  void getForADifferentUserIs404EvenThoughTheSagaExists() throws Exception {
    // other-docs/10 Decision 7: scoped by (sagaId, userId) — a saga
    // belonging to a different user reads back as 404, indistinguishable
    // from a saga that never existed.
    UUID sagaId = UUID.randomUUID();
    restTemplate.exchange("/internal/sagas", HttpMethod.POST,
        new HttpEntity<>(createBody(sagaId, "user-1"), headersWithToken(serviceToken())), Map.class);

    ResponseEntity<Map> response = restTemplate.exchange(
        "/internal/sagas/" + sagaId + "?onBehalfOfUserId=user-2",
        HttpMethod.GET,
        new HttpEntity<>(null, headersWithToken(serviceToken())),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void creatingTheSameSagaTwiceIsIdempotentAndHoldsOnlyOnce() throws Exception {
    UUID sagaId = UUID.randomUUID();
    Map<String, Object> body = createBody(sagaId, "user-2");

    restTemplate.exchange("/internal/sagas", HttpMethod.POST,
        new HttpEntity<>(body, headersWithToken(serviceToken())), Map.class);
    restTemplate.exchange("/internal/sagas", HttpMethod.POST,
        new HttpEntity<>(body, headersWithToken(serviceToken())), Map.class);

    verify(ledgerServiceClient, times(1)).hold(any(), anyString(), any(), any(), anyString());
  }

  @Test
  void ordinaryEndUserTokenIsForbidden() throws Exception {
    UUID sagaId = UUID.randomUUID();
    String endUserToken = token("real-user", List.of());

    ResponseEntity<Map> response = restTemplate.exchange(
        "/internal/sagas",
        HttpMethod.POST,
        new HttpEntity<>(createBody(sagaId, "user-1"), headersWithToken(endUserToken)),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void missingBearerTokenIsRejected() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<Map> response = restTemplate.exchange(
        "/internal/sagas",
        HttpMethod.POST,
        new HttpEntity<>(createBody(UUID.randomUUID(), "user-1"), headers),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void getOnAnUnknownSagaIs404() throws Exception {
    ResponseEntity<Map> response = restTemplate.exchange(
        "/internal/sagas/" + UUID.randomUUID() + "?onBehalfOfUserId=user-1",
        HttpMethod.GET,
        new HttpEntity<>(null, headersWithToken(serviceToken())),
        Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
