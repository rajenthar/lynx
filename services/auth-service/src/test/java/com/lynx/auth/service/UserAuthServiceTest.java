package com.lynx.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.auth.domain.User;
import com.lynx.auth.repository.EmailOtpRepository;
import com.lynx.auth.repository.UserRepository;
import com.lynx.auth.security.SigningKey;
import com.lynx.common.error.AuthException;
import com.lynx.common.error.ConflictException;
import com.lynx.common.error.ValidationException;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * {@code OtpService} is real too (backed by a mocked {@code MailService} so
 * no actual SMTP call happens) — same reasoning, plus it lets
 * {@code registerSendsAnOtpEmail} assert on what was ACTUALLY sent.
 */
class UserAuthServiceTest {

  private UserRepository userRepository;
  private BCryptPasswordEncoder passwordEncoder;
  private RecordingMailService mailService;
  private UserAuthService userAuthService;

  private static final class RecordingMailService implements MailService {
    final List<String[]> sent = new ArrayList<>();

    @Override
    public void sendOtpEmail(String toEmail, String otpCode) {
      sent.add(new String[] {toEmail, otpCode});
    }
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    when(userRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
    passwordEncoder = new BCryptPasswordEncoder();
    JwtIssuer jwtIssuer = new JwtIssuer(new SigningKey());
    mailService = new RecordingMailService();
    EmailOtpRepository otpRepository = mock(EmailOtpRepository.class);
    when(otpRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
    OtpService otpService = new OtpService(otpRepository, userRepository, mailService);
    userAuthService = new UserAuthService(userRepository, passwordEncoder, jwtIssuer, otpService);
  }

  @Test
  void registerRejectsAShortPassword() {
    assertThatThrownBy(() -> userAuthService.register("a@b.com", "sh0rt!", "Alice"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void registerRejectsAPasswordWithNoDigit() {
    assertThatThrownBy(() -> userAuthService.register("a@b.com", "longenough!", "Alice"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void registerRejectsAPasswordWithNoSpecialCharacter() {
    assertThatThrownBy(() -> userAuthService.register("a@b.com", "longenough1", "Alice"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void registerRejectsADuplicateVerifiedEmail() {
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(verifiedUser("a@b.com", "whatever1!")));

    assertThatThrownBy(() -> userAuthService.register("a@b.com", "longenough1!", "Alice"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void registerOverwritesAnUnverifiedAccountInPlaceInsteadOfRejectingIt() {
    User existing = new User(
        UUID.randomUUID(), "a@b.com", "Old Name", passwordEncoder.encode("oldpassword1!"), Instant.now());
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(existing));

    User result = userAuthService.register("a@b.com", "newpassword1!", "New Name");

    assertThat(result.getId()).isEqualTo(existing.getId());
    assertThat(result.getName()).isEqualTo("New Name");
    assertThat(passwordEncoder.matches("newpassword1!", result.getPasswordHash())).isTrue();
    assertThat(result.isEmailVerified()).isFalse();
    // A fresh OTP for the new attempt, not the abandoned one.
    assertThat(mailService.sent).hasSize(1);
  }

  @Test
  void registerNormalizesEmailAndHashesThePasswordBeforeSaving() {
    userAuthService.register("A@B.COM", "longenough1!", "Alice");

    var captor = org.mockito.ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(captor.capture());
    User saved = captor.getValue();
    assertThat(saved.getEmail()).isEqualTo("a@b.com");
    assertThat(saved.getPasswordHash()).isNotEqualTo("longenough1!");
    assertThat(passwordEncoder.matches("longenough1!", saved.getPasswordHash())).isTrue();
  }

  @Test
  void registerCreatesAnUnverifiedUserAndSendsAnOtpEmail() {
    User user = userAuthService.register("a@b.com", "longenough1!", "Alice");

    assertThat(user.isEmailVerified()).isFalse();
    assertThat(mailService.sent).hasSize(1);
    assertThat(mailService.sent.get(0)[0]).isEqualTo("a@b.com");
    assertThat(mailService.sent.get(0)[1]).matches("\\d{6}");
  }

  @Test
  void loginFailsWithTheSameMessageForAnUnknownEmailAsForAWrongPassword() {
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());
    AuthException unknownEmail = catchAuthException(() -> userAuthService.login("a@b.com", "whatever"));

    User user = verifiedUser("a@b.com", "correct-pw");
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
    AuthException wrongPassword = catchAuthException(() -> userAuthService.login("a@b.com", "wrong-pw"));

    assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
  }

  @Test
  void loginRejectsAnUnverifiedAccountEvenWithTheCorrectPassword() {
    User user = new User(UUID.randomUUID(), "a@b.com", "Alice", passwordEncoder.encode("correct-pw"), Instant.now());
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> userAuthService.login("a@b.com", "correct-pw"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void loginSucceedsWithTheCorrectPasswordAndTheTokenCarriesTheUsersRealIdentity() throws Exception {
    User user = verifiedUser("a@b.com", "correct-pw");
    when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));

    JwtIssuer.IssuedToken token = userAuthService.login("a@b.com", "correct-pw");

    SignedJWT jwt = SignedJWT.parse(token.value());
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.getId().toString());
    assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("a@b.com");
    assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("user");
  }

  @Test
  void changeDetailsRejectsAWrongCurrentPassword() {
    User user = verifiedUser("a@b.com", "correct-pw1!");
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> userAuthService.changeDetails(user.getId(), "wrong-pw", "New Name", null))
        .isInstanceOf(AuthException.class);
  }

  @Test
  void changeDetailsRejectsAnInvalidNewPasswordEvenWithTheCorrectCurrentOne() {
    User user = verifiedUser("a@b.com", "correct-pw1!");
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> userAuthService.changeDetails(user.getId(), "correct-pw1!", null, "nodigitnorspecial"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void changeDetailsUpdatesOnlyTheFieldsProvided() {
    User user = verifiedUser("a@b.com", "correct-pw1!");
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

    User result = userAuthService.changeDetails(user.getId(), "correct-pw1!", "New Name", null);

    assertThat(result.getName()).isEqualTo("New Name");
    // Password untouched — the OLD current password still matches.
    assertThat(passwordEncoder.matches("correct-pw1!", result.getPasswordHash())).isTrue();
  }

  @Test
  void changeDetailsCanUpdateThePasswordToo() {
    User user = verifiedUser("a@b.com", "correct-pw1!");
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

    User result = userAuthService.changeDetails(user.getId(), "correct-pw1!", null, "newpassword1!");

    assertThat(passwordEncoder.matches("newpassword1!", result.getPasswordHash())).isTrue();
  }

  private User verifiedUser(String email, String rawPassword) {
    User user = new User(UUID.randomUUID(), email, "Alice", passwordEncoder.encode(rawPassword), Instant.now());
    user.markEmailVerified(Instant.now());
    return user;
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
