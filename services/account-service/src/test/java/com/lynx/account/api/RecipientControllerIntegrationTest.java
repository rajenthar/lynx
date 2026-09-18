package com.lynx.account.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynx.account.client.LedgerServiceClient;
import com.lynx.account.dto.RecipientDtos.SaveRecipientRequest;
import com.lynx.account.dto.RecipientDtos.SavedRecipientView;
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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres via Testcontainers, real Flyway migrations, the full app on
 * a random port — same shape as {@code AccountControllerIntegrationTest};
 * {@link LedgerServiceClient} is still replaced with a no-op double since
 * the app context wires it regardless, even though these endpoints never
 * call it.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "spring.main.allow-bean-definition-overriding=true")
class RecipientControllerIntegrationTest {

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
    registry.add("spring.kafka.listener.auto-startup", () -> "false");
  }

  @BeforeAll
  static void generateSigningKey() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
  }

  static final class NoOpLedgerServiceClient extends LedgerServiceClient {
    NoOpLedgerServiceClient() {
      super(null, null, null);
    }
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    public JwtVerifier jwtVerifier() {
      JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
      return new JwtVerifier(jwks, ISSUER, AUDIENCE);
    }

    @Bean
    @Primary
    public LedgerServiceClient ledgerServiceClient() {
      return new NoOpLedgerServiceClient();
    }
  }

  @Autowired
  private TestRestTemplate restTemplate;

  @BeforeEach
  void useApacheHttpClient() {
    restTemplate.getRestTemplate().setRequestFactory(
        new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
  }

  private static String token(String userId) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(userId)
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .claim("roles", List.of())
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private HttpHeaders headersFor(String userId) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token(userId));
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  @Test
  void savingANewRecipientThenListingReturnsIt() throws Exception {
    String userId = "user-" + UUID.randomUUID();
    HttpEntity<SaveRecipientRequest> request =
        new HttpEntity<>(new SaveRecipientRequest("Bob", "recipient-1"), headersFor(userId));

    ResponseEntity<SavedRecipientView> saveResponse =
        restTemplate.postForEntity("/v1/recipients", request, SavedRecipientView.class);
    assertThat(saveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(saveResponse.getBody().label()).isEqualTo("Bob");
    assertThat(saveResponse.getBody().recipientUserId()).isEqualTo("recipient-1");

    ResponseEntity<SavedRecipientView[]> listResponse = restTemplate.exchange(
        "/v1/recipients", HttpMethod.GET, new HttpEntity<>(headersFor(userId)), SavedRecipientView[].class);
    assertThat(listResponse.getBody()).hasSize(1);
    assertThat(listResponse.getBody()[0].label()).isEqualTo("Bob");
  }

  @Test
  void savingTheSameRecipientAgainUpdatesTheLabelRatherThanDuplicating() throws Exception {
    String userId = "user-" + UUID.randomUUID();
    HttpHeaders headers = headersFor(userId);

    restTemplate.postForEntity("/v1/recipients",
        new HttpEntity<>(new SaveRecipientRequest("Bob", "recipient-2"), headers), SavedRecipientView.class);
    restTemplate.postForEntity("/v1/recipients",
        new HttpEntity<>(new SaveRecipientRequest("Bobby", "recipient-2"), headers), SavedRecipientView.class);

    ResponseEntity<SavedRecipientView[]> listResponse = restTemplate.exchange(
        "/v1/recipients", HttpMethod.GET, new HttpEntity<>(headers), SavedRecipientView[].class);
    assertThat(listResponse.getBody()).hasSize(1);
    assertThat(listResponse.getBody()[0].label()).isEqualTo("Bobby");
  }

  @Test
  void aDifferentRecipientClaimingATakenLabelIs409() throws Exception {
    String userId = "user-" + UUID.randomUUID();
    HttpHeaders headers = headersFor(userId);

    restTemplate.postForEntity("/v1/recipients",
        new HttpEntity<>(new SaveRecipientRequest("Bob", "recipient-3"), headers), SavedRecipientView.class);

    ResponseEntity<String> conflict = restTemplate.postForEntity("/v1/recipients",
        new HttpEntity<>(new SaveRecipientRequest("Bob", "recipient-4"), headers), String.class);
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void oneUsersRecipientsAreInvisibleToAnotherUser() throws Exception {
    String userA = "user-" + UUID.randomUUID();
    String userB = "user-" + UUID.randomUUID();
    restTemplate.postForEntity("/v1/recipients",
        new HttpEntity<>(new SaveRecipientRequest("Bob", "recipient-5"), headersFor(userA)), SavedRecipientView.class);

    ResponseEntity<SavedRecipientView[]> userBList = restTemplate.exchange(
        "/v1/recipients", HttpMethod.GET, new HttpEntity<>(headersFor(userB)), SavedRecipientView[].class);
    assertThat(userBList.getBody()).isEmpty();
  }

  @Test
  void removingARecipientThatBelongsToSomeoneElseIs404() throws Exception {
    String owner = "user-" + UUID.randomUUID();
    String other = "user-" + UUID.randomUUID();
    ResponseEntity<SavedRecipientView> saved = restTemplate.postForEntity("/v1/recipients",
        new HttpEntity<>(new SaveRecipientRequest("Bob", "recipient-6"), headersFor(owner)), SavedRecipientView.class);
    UUID id = saved.getBody().id();

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/recipients/" + id, HttpMethod.DELETE, new HttpEntity<>(headersFor(other)), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void removingAnOwnedRecipientActuallyRemovesIt() throws Exception {
    String userId = "user-" + UUID.randomUUID();
    HttpHeaders headers = headersFor(userId);
    ResponseEntity<SavedRecipientView> saved = restTemplate.postForEntity("/v1/recipients",
        new HttpEntity<>(new SaveRecipientRequest("Bob", "recipient-7"), headers), SavedRecipientView.class);
    UUID id = saved.getBody().id();

    ResponseEntity<Void> deleteResponse = restTemplate.exchange(
        "/v1/recipients/" + id, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<SavedRecipientView[]> listResponse = restTemplate.exchange(
        "/v1/recipients", HttpMethod.GET, new HttpEntity<>(headers), SavedRecipientView[].class);
    assertThat(listResponse.getBody()).isEmpty();
  }
}
