package com.lynx.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynx.auth.domain.ServiceClient;
import com.lynx.auth.repository.ServiceClientRepository;
import com.lynx.common.error.AuthException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ServiceTokenIssuerServiceTest {

  private ServiceClientRepository serviceClientRepository;
  private BCryptPasswordEncoder passwordEncoder;
  private ServiceTokenIssuerService service;

  @BeforeEach
  void setUp() {
    serviceClientRepository = mock(ServiceClientRepository.class);
    passwordEncoder = new BCryptPasswordEncoder();
    service = new ServiceTokenIssuerService(
        serviceClientRepository, passwordEncoder, new JwtIssuer(new com.lynx.auth.security.SigningKey()));
  }

  @Test
  void rejectsAnUnsupportedGrantType() {
    assertThatThrownBy(() -> service.issueToken("password", "saga-orchestrator", "secret"))
        .isInstanceOf(AuthException.class);
  }

  @Test
  void rejectsAnUnknownClientId() {
    when(serviceClientRepository.findByClientId("unknown")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.issueToken("client_credentials", "unknown", "secret"))
        .isInstanceOf(AuthException.class);
  }

  @Test
  void rejectsAWrongSecretWithTheSameMessageAsAnUnknownClientId() {
    ServiceClient client = new ServiceClient(
        "saga-orchestrator", passwordEncoder.encode("correct-secret"),
        new String[] {"internal-service"}, Instant.now());
    when(serviceClientRepository.findByClientId("saga-orchestrator")).thenReturn(Optional.of(client));
    when(serviceClientRepository.findByClientId("unknown")).thenReturn(Optional.empty());

    AuthException unknownClient = catchAuthException(
        () -> service.issueToken("client_credentials", "unknown", "whatever"));
    AuthException wrongSecret = catchAuthException(
        () -> service.issueToken("client_credentials", "saga-orchestrator", "wrong-secret"));

    assertThat(unknownClient.getMessage()).isEqualTo(wrongSecret.getMessage());
  }

  @Test
  void issuesATokenCarryingTheClientsOwnRegisteredRoles() {
    ServiceClient client = new ServiceClient(
        "saga-orchestrator", passwordEncoder.encode("correct-secret"),
        new String[] {"internal-service"}, Instant.now());
    when(serviceClientRepository.findByClientId("saga-orchestrator")).thenReturn(Optional.of(client));

    JwtIssuer.IssuedToken token =
        service.issueToken("client_credentials", "saga-orchestrator", "correct-secret");

    assertThat(token.value()).isNotBlank();
    assertThat(token.expiresInSeconds()).isEqualTo(60 * 60);
  }

  private static AuthException catchAuthException(Runnable action) {
    try {
      action.run();
    } catch (AuthException e) {
      return e;
    }
    throw new AssertionError("Expected an AuthException");
  }
}
