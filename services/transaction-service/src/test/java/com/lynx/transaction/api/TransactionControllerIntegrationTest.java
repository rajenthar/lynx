package com.lynx.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynx.security.JwtVerifier;
import com.lynx.transaction.client.AccountServiceClient;
import com.lynx.transaction.client.AccountServiceClient.ResolvedTransferAccounts;
import com.lynx.transaction.client.DownstreamRejectedException;
import com.lynx.transaction.client.SagaOrchestratorClient;
import com.lynx.transaction.dto.TransactionDtos.CreateTransferRequest;
import com.lynx.transaction.dto.TransactionDtos.TransferAcceptedView;
import com.lynx.transaction.dto.TransactionDtos.TransferStatusView;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The full app on a random port, real end-user JWT verification — but
 * NOT real {@code saga-orchestrator}/{@code account-service} instances:
 * both clients are replaced with recording test doubles (this service
 * owns no database, so no Testcontainers Postgres is needed at all, unlike
 * every other service's own controller integration test).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "spring.main.allow-bean-definition-overriding=true")
class TransactionControllerIntegrationTest {

  private static final String ISSUER = "https://auth.lynx";
  private static final String AUDIENCE = "lynx-api";
  private static RSAKey signingKey;

  @BeforeAll
  static void generateSigningKey() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
  }

  static final class RecordingAccountServiceClient extends AccountServiceClient {
    ResolvedTransferAccounts onResolve =
        new ResolvedTransferAccounts(UUID.randomUUID(), UUID.randomUUID());
    RuntimeException resolveFailure;

    RecordingAccountServiceClient() {
      super(null, null, null);
    }

    @Override
    public ResolvedTransferAccounts resolveTransferAccounts(
        String senderUserId, String senderCurrency, String recipientUserId, String recipientCurrency) {
      if (resolveFailure != null) {
        throw resolveFailure;
      }
      return onResolve;
    }
  }

  static final class RecordingSagaOrchestratorClient extends SagaOrchestratorClient {
    UUID lastCreatedSagaId;

    RecordingSagaOrchestratorClient() {
      super(null, null, null);
    }

    @Override
    public SagaSummary createSaga(UUID sagaId, String userId, UUID fromAccountId, UUID toAccountId,
                                   BigDecimal amount, String fromCurrency, String toCurrency) {
      lastCreatedSagaId = sagaId;
      return new SagaSummary(sagaId, "HOLDING", null, Instant.now(), Instant.now());
    }

    @Override
    public SagaSummary getSaga(UUID sagaId, String userId) {
      return new SagaSummary(sagaId, "SETTLED", null, Instant.now(), Instant.now());
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
    public AccountServiceClient accountServiceClient() {
      return new RecordingAccountServiceClient();
    }

    @Bean
    @Primary
    public SagaOrchestratorClient sagaOrchestratorClient() {
      return new RecordingSagaOrchestratorClient();
    }
  }

  @Autowired
  private TestRestTemplate restTemplate;
  @Autowired
  private AccountServiceClient accountServiceClient;
  @Autowired
  private SagaOrchestratorClient sagaOrchestratorClient;

  @org.junit.jupiter.api.BeforeEach
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

  private HttpHeaders headersFor(String userId, String idempotencyKey) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token(userId));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return headers;
  }

  @Test
  void createTransferReturns202WithADeterministicSagaId() throws Exception {
    String idempotencyKey = UUID.randomUUID().toString();
    ResponseEntity<TransferAcceptedView> response = restTemplate.exchange(
        "/v1/transfers", HttpMethod.POST,
        new HttpEntity<>(new CreateTransferRequest("recipient-1", "SGD", "USD", new BigDecimal("50.00")),
            headersFor("user-1", idempotencyKey)),
        TransferAcceptedView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody().status()).isEqualTo("HOLDING");

    UUID expectedSagaId = com.lynx.idempotency.SagaIds.deriveSagaId(
        "user-1", com.lynx.idempotency.IdempotencyKey.fromClientHeader(idempotencyKey));
    assertThat(response.getBody().sagaId()).isEqualTo(expectedSagaId);
    assertThat(((RecordingSagaOrchestratorClient) sagaOrchestratorClient).lastCreatedSagaId)
        .isEqualTo(expectedSagaId);
  }

  @Test
  void createTransferIs404WhenTheCallerHasNoAccountInTheFromCurrency() throws Exception {
    ((RecordingAccountServiceClient) accountServiceClient).resolveFailure =
        new DownstreamRejectedException(404, "No SGD account found for userId=user-2");

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/transfers", HttpMethod.POST,
        new HttpEntity<>(new CreateTransferRequest("recipient-1", "SGD", "USD", new BigDecimal("50.00")),
            headersFor("user-2", UUID.randomUUID().toString())),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void createTransferIs404WhenTheRecipientHasNoAccountInTheToCurrency() throws Exception {
    ((RecordingAccountServiceClient) accountServiceClient).resolveFailure =
        new DownstreamRejectedException(404, "Recipient has no JPY account: userId=nobody");

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/transfers", HttpMethod.POST,
        new HttpEntity<>(new CreateTransferRequest("nobody", "SGD", "JPY", new BigDecimal("50.00")),
            headersFor("user-3", UUID.randomUUID().toString())),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void getTransferProxiesTheSagaOrchestratorsStatus() throws Exception {
    UUID sagaId = UUID.randomUUID();

    ResponseEntity<TransferStatusView> response = restTemplate.exchange(
        "/v1/transfers/" + sagaId, HttpMethod.GET,
        new HttpEntity<>(headersFor("user-1", null)),
        TransferStatusView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().sagaId()).isEqualTo(sagaId);
    assertThat(response.getBody().status()).isEqualTo("SETTLED");
  }

  @Test
  void missingBearerTokenIsRejected() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/transfers/" + UUID.randomUUID(), HttpMethod.GET,
        new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
