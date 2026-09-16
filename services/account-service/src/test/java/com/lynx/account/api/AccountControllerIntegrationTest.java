package com.lynx.account.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynx.account.client.LedgerServiceClient;
import com.lynx.account.dto.AccountDtos.AccountView;
import com.lynx.account.dto.AccountDtos.CreateAccountRequest;
import com.lynx.account.dto.AccountDtos.DepositAcceptedView;
import com.lynx.account.dto.AccountDtos.DepositRequest;
import com.lynx.account.dto.AccountDtos.ResolveTransferRequest;
import com.lynx.account.dto.AccountDtos.ResolvedTransferAccountsView;
import com.lynx.money.Money;
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
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Postgres via Testcontainers, real Flyway migrations, the full app on
 * a random port — but NOT a real {@code ledger-service} or Kafka broker
 * (see {@code EventProjectorIntegrationTest}'s own test-boundary note):
 * {@link LedgerServiceClient} is replaced with a recording test double, and
 * the Kafka listener container is disabled at startup, exactly the same
 * reasoning as {@code EventProjectorIntegrationTest}'s own
 * {@code spring.kafka.listener.auto-startup=false}.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "spring.main.allow-bean-definition-overriding=true")
class AccountControllerIntegrationTest {

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

  /** Records every deposit call — a hand-rolled test double, same reasoning as {@code AccountServiceTest}'s own. */
  static final class RecordingLedgerServiceClient extends LedgerServiceClient {
    final List<Object[]> calls = new CopyOnWriteArrayList<>();

    RecordingLedgerServiceClient() {
      super(null, null, null);
    }

    @Override
    public void deposit(UUID depositId, String userId, UUID accountId, Money amount) {
      calls.add(new Object[] {depositId, userId, accountId, amount});
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
      return new RecordingLedgerServiceClient();
    }
  }

  @Autowired
  private TestRestTemplate restTemplate;
  @Autowired
  private LedgerServiceClient ledgerServiceClient;

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
        .expirationTime(Date.from(java.time.Instant.now().plusSeconds(300)))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private HttpHeaders headersFor(String userId) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token(userId));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return headers;
  }

  private static String serviceToken(String clientId) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(clientId)
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .claim("roles", List.of("internal-service"))
        .expirationTime(Date.from(java.time.Instant.now().plusSeconds(300)))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  private HttpHeaders serviceHeadersFor(String clientId) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(serviceToken(clientId));
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return headers;
  }

  @Test
  void createAccountReturnsAZeroBalanceAccountOwnedByTheCaller() throws Exception {
    ResponseEntity<AccountView> response = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-1")),
        AccountView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().userId()).isEqualTo("user-1");
    assertThat(response.getBody().currency()).isEqualTo("SGD");
    assertThat(response.getBody().available()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void aSecondAccountInTheSameCurrencyForTheSameUserIs409() throws Exception {
    restTemplate.exchange("/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-7")), AccountView.class);

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-7")), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void differentCurrenciesForTheSameUserAreBothAllowed() throws Exception {
    ResponseEntity<AccountView> sgd = restTemplate.exchange("/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-8")), AccountView.class);
    ResponseEntity<AccountView> usd = restTemplate.exchange("/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("USD"), headersFor("user-8")), AccountView.class);

    assertThat(sgd.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(usd.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(sgd.getBody().id()).isNotEqualTo(usd.getBody().id());
  }

  @Test
  void getAccountOwnedBySomeoneElseIs404() throws Exception {
    ResponseEntity<AccountView> created = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-9")),
        AccountView.class);
    UUID accountId = created.getBody().id();

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/accounts/" + accountId, HttpMethod.GET,
        new HttpEntity<>(null, headersFor("user-2")),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void listAccountsReturnsOnlyTheCallersOwn() throws Exception {
    restTemplate.exchange("/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-3")), AccountView.class);
    restTemplate.exchange("/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("USD"), headersFor("user-4")), AccountView.class);

    ResponseEntity<List> response = restTemplate.exchange(
        "/v1/accounts", HttpMethod.GET, new HttpEntity<>(null, headersFor("user-3")), List.class);

    assertThat(response.getBody()).hasSize(1);
  }

  @Test
  void depositCallsLedgerServiceAndReturnsAcceptedNotTheFinalBalance() throws Exception {
    ResponseEntity<AccountView> created = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-5")),
        AccountView.class);
    UUID accountId = created.getBody().id();

    ResponseEntity<DepositAcceptedView> response = restTemplate.exchange(
        "/v1/accounts/" + accountId + "/deposit", HttpMethod.POST,
        new HttpEntity<>(new DepositRequest(new BigDecimal("100.00"), "SGD"), headersFor("user-5")),
        DepositAcceptedView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().accountId()).isEqualTo(accountId);
    RecordingLedgerServiceClient recording = (RecordingLedgerServiceClient) ledgerServiceClient;
    assertThat(recording.calls).anySatisfy(call -> {
      assertThat(call[1]).isEqualTo("user-5");
      assertThat(call[2]).isEqualTo(accountId);
    });

    // Balance is NOT updated synchronously — only the Kafka projection does
    // that, and no real broker is running in this test.
    ResponseEntity<AccountView> afterDeposit = restTemplate.exchange(
        "/v1/accounts/" + accountId, HttpMethod.GET, new HttpEntity<>(null, headersFor("user-5")),
        AccountView.class);
    assertThat(afterDeposit.getBody().available()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void depositRejectsACurrencyMismatch() throws Exception {
    ResponseEntity<AccountView> created = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-6")),
        AccountView.class);
    UUID accountId = created.getBody().id();

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/accounts/" + accountId + "/deposit", HttpMethod.POST,
        new HttpEntity<>(new DepositRequest(new BigDecimal("100.00"), "USD"), headersFor("user-6")),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void missingBearerTokenIsRejected() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void resolveTransferReturnsBothAccountIdsForAServiceCaller() throws Exception {
    ResponseEntity<AccountView> sender = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-10")),
        AccountView.class);
    ResponseEntity<AccountView> recipient = restTemplate.exchange(
        "/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("USD"), headersFor("recipient-1")),
        AccountView.class);

    ResponseEntity<ResolvedTransferAccountsView> response = restTemplate.exchange(
        "/internal/accounts/resolve-transfer", HttpMethod.POST,
        new HttpEntity<>(new ResolveTransferRequest("user-10", "SGD", "recipient-1", "USD"),
            serviceHeadersFor("transaction-service")),
        ResolvedTransferAccountsView.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().senderAccountId()).isEqualTo(sender.getBody().id());
    assertThat(response.getBody().recipientAccountId()).isEqualTo(recipient.getBody().id());
  }

  @Test
  void resolveTransferIs404WhenTheSenderHasNoAccountInThatCurrency() throws Exception {
    ResponseEntity<String> response = restTemplate.exchange(
        "/internal/accounts/resolve-transfer", HttpMethod.POST,
        new HttpEntity<>(new ResolveTransferRequest("nobody", "JPY", "recipient-1", "USD"),
            serviceHeadersFor("transaction-service")),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void resolveTransferIs404WhenTheRecipientHasNoAccountInThatCurrency() throws Exception {
    restTemplate.exchange("/v1/accounts", HttpMethod.POST,
        new HttpEntity<>(new CreateAccountRequest("SGD"), headersFor("user-13")), AccountView.class);

    ResponseEntity<String> response = restTemplate.exchange(
        "/internal/accounts/resolve-transfer", HttpMethod.POST,
        new HttpEntity<>(new ResolveTransferRequest("user-13", "SGD", "nobody", "JPY"),
            serviceHeadersFor("transaction-service")),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void resolveTransferRejectsAnOrdinaryEndUserToken() throws Exception {
    ResponseEntity<String> response = restTemplate.exchange(
        "/internal/accounts/resolve-transfer", HttpMethod.POST,
        new HttpEntity<>(new ResolveTransferRequest("user-10", "SGD", "recipient-1", "USD"),
            headersFor("user-1")),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
