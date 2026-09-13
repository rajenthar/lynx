package com.lynx.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynx.auth.dto.AuthDtos.LoginRequest;
import com.lynx.auth.dto.AuthDtos.RegisterRequest;
import com.lynx.auth.dto.AuthDtos.TokenResponse;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres 16 via Testcontainers, real Flyway migrations, the full
 * running application on a random port — proves the four public endpoints
 * (register/login/token/jwks) work together, not just in isolation: a
 * registered user's login token verifies against the SAME JWKS this
 * instance publishes, and the seeded {@code saga-orchestrator} client can
 * actually complete the Client Credentials grant.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuthServiceIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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

  @BeforeEach
  void useApacheHttpClient() {
    // Same JDK auth-retry quirk LedgerControllerIntegrationTest works around
    // — the default HttpURLConnection-based factory can throw on a POST to
    // an endpoint returning a non-2xx status (register's duplicate-email
    // 409, login's/token's 401s, all exercised below).
    restTemplate.getRestTemplate().setRequestFactory(
        new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
  }

  @Test
  void registerReturnsATokenAndASecondRegisterWithTheSameEmailIsRejected() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    RegisterRequest request = new RegisterRequest(email, "longenoughpw", "Alice");

    ResponseEntity<TokenResponse> first =
        restTemplate.postForEntity("/auth/register", request, TokenResponse.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody().accessToken()).isNotBlank();
    assertThat(first.getBody().tokenType()).isEqualTo("Bearer");
    assertThat(first.getBody().expiresIn()).isEqualTo(30 * 60);

    ResponseEntity<String> duplicate =
        restTemplate.postForEntity("/auth/register", request, String.class);
    assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void loginWithTheRightPasswordSucceedsAndTheTokenVerifiesAgainstThisInstancesOwnJwks() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    restTemplate.postForEntity("/auth/register", new RegisterRequest(email, "correct-pw!!", "Bob"), TokenResponse.class);

    ResponseEntity<TokenResponse> loginResponse = restTemplate.postForEntity(
        "/auth/login", new LoginRequest(email, "correct-pw!!"), TokenResponse.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    SignedJWT jwt = SignedJWT.parse(loginResponse.getBody().accessToken());
    assertThat(jwt.getJWTClaimsSet().getSubject()).isNotBlank();
    assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo(email);
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("user");

    ResponseEntity<String> jwksResponse = restTemplate.getForEntity("/auth/.well-known/jwks.json", String.class);
    JsonNode jwks = MAPPER.readTree(jwksResponse.getBody());
    RSAKey publicKey = RSAKey.parse(jwks.get("keys").get(0).toString());
    assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
  }

  @Test
  void loginWithAWrongPasswordIs401() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    restTemplate.postForEntity("/auth/register", new RegisterRequest(email, "correct-pw!!", "Carol"), TokenResponse.class);

    ResponseEntity<String> response =
        restTemplate.postForEntity("/auth/login", new LoginRequest(email, "wrong-pw"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void theSeededSagaOrchestratorClientCanCompleteTheClientCredentialsGrant() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(
        "saga-orchestrator:placeholder-not-a-real-secret".getBytes(StandardCharsets.UTF_8)));
    HttpEntity<String> request = new HttpEntity<>("grant_type=client_credentials", headers);

    ResponseEntity<TokenResponse> response =
        restTemplate.postForEntity("/auth/token", request, TokenResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
    assertThat(response.getBody().expiresIn()).isEqualTo(60 * 60);
    SignedJWT jwt = SignedJWT.parse(response.getBody().accessToken());
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("saga-orchestrator");
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("internal-service");
  }

  @Test
  void theClientCredentialsGrantWithAWrongSecretIs401() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(
        "saga-orchestrator:wrong-secret".getBytes(StandardCharsets.UTF_8)));
    HttpEntity<String> request = new HttpEntity<>("grant_type=client_credentials", headers);

    ResponseEntity<String> response = restTemplate.postForEntity("/auth/token", request, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
