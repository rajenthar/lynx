package com.lynx.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.auth.domain.User;
import com.lynx.auth.repository.UserRepository;
import com.lynx.auth.security.SigningKey;
import com.lynx.common.error.AuthException;
import com.lynx.common.error.ConflictException;
import com.lynx.common.error.ValidationException;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Mocked {@link UserRepository} — the Postgres-backed uniqueness check
 * itself is proven in {@code AuthServiceIntegrationTest}. {@link JwtIssuer}
 * is used for real, not mocked — it's a plain, cheap-to-construct signer
 * with no I/O, and Mockito's inline mock maker can't instrument a concrete
 * class on this JDK anyway (Byte Buddy/JDK 25); asserting the real signed
 * JWT's claims is a stronger proof than a stubbed return value would be.
 */
class UserAuthServiceTest {

  private UserRepository userRepository;
  private BCryptPasswordEncoder passwordEncoder;
  private UserAuthService userAuthService;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    passwordEncoder = new BCryptPasswordEncoder();
    JwtIssuer jwtIssuer = new JwtIssuer(new SigningKey());
    userAuthService = new UserAuthService(userRepository, passwordEncoder, jwtIssuer);
  }

  @Test
  void registerRejectsAShortPassword() {
    assertThatThrownBy(() -> userAuthService.register("a@b.com", "short", "Alice"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void registerRejectsADuplicateEmail() {
    when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

    assertThatThrownBy(() -> userAuthService.register("a@b.com", "longenoughpw", "Alice"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void registerNormalizesEmailAndHashesThePasswordBeforeSaving() {
    userAuthService.register("A@B.COM", "longenoughpw", "Alice");

    var captor = org.mockito.ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    User saved = captor.getValue();
    assertThat(saved.getEmail()).isEqualTo("a@b.com");
    assertThat(saved.getPasswordHash()).isNotEqualTo("longenoughpw");
    assertThat(passwordEncoder.matches("longenoughpw", saved.getPasswordHash())).isTrue();
  }

  @Test
  void loginFailsWithTheSameMessageForAnUnknownEmailAsForAWrongPassword() {
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());
    AuthException unknownEmail = catchAuthException(() -> userAuthService.login("a@b.com", "whatever"));

    User user = new User(UUID.randomUUID(), "a@b.com", "Alice", passwordEncoder.encode("correct-pw"), Instant.now());
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
    AuthException wrongPassword = catchAuthException(() -> userAuthService.login("a@b.com", "wrong-pw"));

    assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
  }

  @Test
  void loginSucceedsWithTheCorrectPasswordAndTheTokenCarriesTheUsersRealIdentity() throws Exception {
    User user = new User(UUID.randomUUID(), "a@b.com", "Alice", passwordEncoder.encode("correct-pw"), Instant.now());
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));

    JwtIssuer.IssuedToken token = userAuthService.login("a@b.com", "correct-pw");

    SignedJWT jwt = SignedJWT.parse(token.value());
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.getId().toString());
    assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("a@b.com");
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("user");
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
