package com.lynx.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynx.auth.dto.AuthDtos.LoginRequest;
import com.lynx.auth.dto.AuthDtos.RegisterRequest;
import com.lynx.auth.dto.AuthDtos.RegisterResponse;
import com.lynx.auth.dto.AuthDtos.ResendOtpRequest;
import com.lynx.auth.dto.AuthDtos.TokenResponse;
import com.lynx.auth.dto.AuthDtos.UserProfileView;
import com.lynx.auth.dto.AuthDtos.VerifyOtpRequest;
import com.lynx.auth.security.SigningKey;
import com.lynx.auth.service.MailService;
import com.lynx.security.JwtVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres 16 via Testcontainers, real Flyway migrations, the full
 * running application on a random port — proves the whole public surface
 * (register/verify-otp/resend-otp/login/me/token/jwks) works together, not
 * just in isolation. The real {@code SmtpMailService} (a genuine outbound
 * SMTP call to Gmail) is replaced with a recording {@code @Primary} fake —
 * exactly the same reasoning as {@code AccountControllerIntegrationTest}
 * replacing {@code LedgerServiceClient}: this test proves the REST/OTP/DB
 * wiring, not that Gmail's SMTP servers are reachable.
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

  @TestConfiguration
  static class RecordingMailConfig {
    @Bean
    @Primary
    MailService recordingMailService() {
      return new RecordingMailService();
    }

    /**
     * Overrides the URL-based {@code JwtVerifier} (which assumes auth-service
     * listens on its normal fixed port 8080) with one backed directly by
     * THIS test instance's own {@link SigningKey} — the random test port
     * never actually serves the real JWKS endpoint the production bean
     * would fetch from.
     */
    @Bean
    @Primary
    JwtVerifier testJwtVerifier(SigningKey signingKey) {
      JWKSet jwks = new JWKSet(signingKey.publicJwk());
      return new JwtVerifier(new ImmutableJWKSet<>(jwks), "https://auth.lynx", "lynx-api");
    }
  }

  static final class RecordingMailService implements MailService {
    final Map<String, String> lastCodeByEmail = new ConcurrentHashMap<>();

    @Override
    public void sendOtpEmail(String toEmail, String otpCode) {
      lastCodeByEmail.put(toEmail, otpCode);
    }
  }

  @Autowired
  private TestRestTemplate restTemplate;

  @Autowired
  private RecordingMailService recordingMailService;

  @BeforeEach
  void useApacheHttpClient() {
    // Same JDK auth-retry quirk LedgerControllerIntegrationTest works around
    // — the default HttpURLConnection-based factory can throw on a POST to
    // an endpoint returning a non-2xx status (register's duplicate-email
    // 409, login's/token's 401s, all exercised below).
    restTemplate.getRestTemplate().setRequestFactory(
        new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
  }

  /** Registers, then verifies with the code the recording fake captured — the shape every other test builds on. */
  private TokenResponse registerAndVerify(String email, String password, String name) {
    restTemplate.postForEntity("/auth/register", new RegisterRequest(email, password, name), RegisterResponse.class);
    String code = recordingMailService.lastCodeByEmail.get(email);
    ResponseEntity<TokenResponse> verifyResponse = restTemplate.postForEntity(
        "/auth/verify-otp", new VerifyOtpRequest(email, code), TokenResponse.class);
    assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return verifyResponse.getBody();
  }

  @Test
  void registerIsAcceptedButNotYetVerified() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    RegisterRequest request = new RegisterRequest(email, "longenough1!", "Alice");

    ResponseEntity<RegisterResponse> first =
        restTemplate.postForEntity("/auth/register", request, RegisterResponse.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(first.getBody().email()).isEqualTo(email);
    assertThat(recordingMailService.lastCodeByEmail.get(email)).matches("\\d{6}");
  }

  @Test
  void aSecondRegisterForAnUnverifiedEmailOverwritesItWithTheNewNameAndPasswordInsteadOfRejectingIt() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    restTemplate.postForEntity(
        "/auth/register", new RegisterRequest(email, "firstpassword1!", "Alice"), RegisterResponse.class);

    ResponseEntity<RegisterResponse> second = restTemplate.postForEntity(
        "/auth/register", new RegisterRequest(email, "secondpassword2@", "Alicia"), RegisterResponse.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    // The OTP from the FIRST attempt no longer verifies this account (the
    // second register issued its own fresh one, currently held in
    // lastCodeByEmail) — only the newest attempt's code works, and it logs
    // in with the newest attempt's password.
    TokenResponse verified = registerAndVerifyAlreadyRegistered(email);
    ResponseEntity<TokenResponse> loginResponse = restTemplate.postForEntity(
        "/auth/login", new LoginRequest(email, "secondpassword2@"), TokenResponse.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(verified.accessToken()).isNotBlank();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(loginResponse.getBody().accessToken());
    ResponseEntity<UserProfileView> profile = restTemplate.exchange(
        "/auth/me", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), UserProfileView.class);
    assertThat(profile.getBody().name()).isEqualTo("Alicia");
  }

  @Test
  void registerRejectsAnEmailThatIsAlreadyFullyVerified() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    registerAndVerify(email, "firstpassword1!", "Alice");

    ResponseEntity<String> duplicate = restTemplate.postForEntity(
        "/auth/register", new RegisterRequest(email, "anotherpassword2!", "Someone Else"), String.class);
    assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void loginBeforeVerifyingTheOtpIsRejected() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    restTemplate.postForEntity("/auth/register", new RegisterRequest(email, "correct-pw1!", "Dana"), RegisterResponse.class);

    ResponseEntity<String> response =
        restTemplate.postForEntity("/auth/login", new LoginRequest(email, "correct-pw1!"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void verifyingWithTheWrongCodeIsRejectedButTheRightCodeThenSucceeds() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    restTemplate.postForEntity("/auth/register", new RegisterRequest(email, "correct-pw1!", "Eve"), RegisterResponse.class);

    ResponseEntity<String> wrongCode = restTemplate.postForEntity(
        "/auth/verify-otp", new VerifyOtpRequest(email, "000000"), String.class);
    assertThat(wrongCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    TokenResponse verified = registerAndVerifyAlreadyRegistered(email);
    assertThat(verified.accessToken()).isNotBlank();
  }

  private TokenResponse registerAndVerifyAlreadyRegistered(String email) {
    String code = recordingMailService.lastCodeByEmail.get(email);
    ResponseEntity<TokenResponse> verifyResponse = restTemplate.postForEntity(
        "/auth/verify-otp", new VerifyOtpRequest(email, code), TokenResponse.class);
    assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return verifyResponse.getBody();
  }

  @Test
  void resendOtpIssuesANewCodeThatWorksEvenAfterTheFirstOneIsIgnored() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    restTemplate.postForEntity("/auth/register", new RegisterRequest(email, "correct-pw1!", "Frank"), RegisterResponse.class);

    restTemplate.postForEntity("/auth/resend-otp", new ResendOtpRequest(email), Void.class);
    String latestCode = recordingMailService.lastCodeByEmail.get(email);

    ResponseEntity<TokenResponse> verifyResponse = restTemplate.postForEntity(
        "/auth/verify-otp", new VerifyOtpRequest(email, latestCode), TokenResponse.class);
    assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void loginWithTheRightPasswordSucceedsAndTheTokenVerifiesAgainstThisInstancesOwnJwks() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    registerAndVerify(email, "correct-pw1!", "Bob");

    ResponseEntity<TokenResponse> loginResponse = restTemplate.postForEntity(
        "/auth/login", new LoginRequest(email, "correct-pw1!"), TokenResponse.class);
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
    registerAndVerify(email, "correct-pw1!", "Carol");

    ResponseEntity<String> response =
        restTemplate.postForEntity("/auth/login", new LoginRequest(email, "wrong-pw"), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void meReturnsTheCallersOwnProfileGivenAValidToken() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    TokenResponse token = registerAndVerify(email, "correct-pw1!", "Grace");

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token.accessToken());
    ResponseEntity<UserProfileView> response = restTemplate.exchange(
        "/auth/me", org.springframework.http.HttpMethod.GET,
        new HttpEntity<>(headers), UserProfileView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().email()).isEqualTo(email);
    assertThat(response.getBody().name()).isEqualTo("Grace");
    assertThat(response.getBody().emailVerified()).isTrue();
  }

  @Test
  void changeDetailsUpdatesNameAndPasswordAfterVerifyingTheCurrentPassword() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    TokenResponse token = registerAndVerify(email, "correct-pw1!", "Henry");

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token.accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    var body = new com.lynx.auth.dto.AuthDtos.ChangeDetailsRequest("correct-pw1!", "Henrietta", "newpassword2!");
    ResponseEntity<UserProfileView> response = restTemplate.exchange(
        "/auth/me", org.springframework.http.HttpMethod.PATCH, new HttpEntity<>(body, headers), UserProfileView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().name()).isEqualTo("Henrietta");

    ResponseEntity<TokenResponse> loginWithNewPassword = restTemplate.postForEntity(
        "/auth/login", new LoginRequest(email, "newpassword2!"), TokenResponse.class);
    assertThat(loginWithNewPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void changeDetailsWithTheWrongCurrentPasswordIs401AndNothingChanges() {
    String email = "user-" + UUID.randomUUID() + "@lynx.test";
    TokenResponse token = registerAndVerify(email, "correct-pw1!", "Ivy");

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token.accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    var body = new com.lynx.auth.dto.AuthDtos.ChangeDetailsRequest("wrong-current-pw", "New Name", null);
    ResponseEntity<String> response = restTemplate.exchange(
        "/auth/me", org.springframework.http.HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<TokenResponse> stillOldPassword = restTemplate.postForEntity(
        "/auth/login", new LoginRequest(email, "correct-pw1!"), TokenResponse.class);
    assertThat(stillOldPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void meWithoutATokenIs401() {
    ResponseEntity<String> response = restTemplate.getForEntity("/auth/me", String.class);
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
