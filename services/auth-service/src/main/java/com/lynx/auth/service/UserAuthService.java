package com.lynx.auth.service;

import com.lynx.auth.domain.User;
import com.lynx.auth.repository.UserRepository;
import com.lynx.common.error.AuthException;
import com.lynx.common.error.ConflictException;
import com.lynx.common.error.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Registration and login — a real Postgres-backed
 * user, BCrypt-hashed password. Registration no longer issues a token
 * immediately — the account exists but stays unverified until the emailed
 * OTP is confirmed via {@code OtpService.verify}, and {@link #login} refuses
 * an unverified account outright. Every registered user gets the plain
 * {@code user} role; there's no admin/elevated role concept yet.
 */
public class UserAuthService {

  static final List<String> DEFAULT_ROLES = List.of("user");
  private static final int MIN_PASSWORD_LENGTH = 8;

  private final UserRepository userRepository;
  private final BCryptPasswordEncoder passwordEncoder;
  private final JwtIssuer jwtIssuer;
  private final OtpService otpService;

  public UserAuthService(
      UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
      JwtIssuer jwtIssuer, OtpService otpService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtIssuer = jwtIssuer;
    this.otpService = otpService;
  }

  /**
   * Creates the user (unverified) and sends the first OTP — no token is
   * issued yet; the caller must call {@code /auth/verify-otp} before they
   * can log in at all.
   *
   * <p>If an account with this email already exists but never completed
   * OTP verification (e.g. the user lost/never received the code, or just
   * gave up), this is treated as "start over," not a conflict: the existing
   * unverified row is updated in place with the newly submitted name and
   * password, and a fresh OTP is issued. A VERIFIED account with this email
   * still refuses outright — that's a real duplicate. This also means the
   * password used to log in afterward is always whichever registration
   * attempt the user most recently completed the OTP step for.
   */
  public User register(String email, String password, String name) {
    if (email == null || email.isBlank()) {
      throw new ValidationException("email is required");
    }
    validatePassword(password);
    if (name == null || name.isBlank()) {
      throw new ValidationException("name is required");
    }
    String normalizedEmail = email.strip().toLowerCase();
    String encodedPassword = passwordEncoder.encode(password);
    String trimmedName = name.strip();

    User user = userRepository.findByEmail(normalizedEmail)
        .map(existing -> {
          if (existing.isEmailVerified()) {
            throw new ConflictException("An account with this email already exists");
          }
          existing.updateRegistrationDetails(trimmedName, encodedPassword, Instant.now());
          return userRepository.save(existing);
        })
        .orElseGet(() -> userRepository.save(
            new User(UUID.randomUUID(), normalizedEmail, trimmedName, encodedPassword, Instant.now())));

    otpService.issueAndSend(user);
    return user;
  }

  private static void validatePassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new ValidationException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
    if (!password.chars().anyMatch(Character::isDigit)) {
      throw new ValidationException("password must contain at least one number");
    }
    if (password.chars().allMatch(Character::isLetterOrDigit)) {
      throw new ValidationException("password must contain at least one special character");
    }
  }

  /**
   * Issues a session token directly for a user who was just OTP-verified —
   * no password check, since successfully consuming a one-time code sent to
   * that email address IS the authentication for this specific step.
   */
  public JwtIssuer.IssuedToken issueTokenForVerifiedUser(User user) {
    return jwtIssuer.issueUserToken(user.getId().toString(), user.getEmail(), DEFAULT_ROLES);
  }

  public JwtIssuer.IssuedToken login(String email, String password) {
    if (email == null || password == null) {
      throw AuthException.unauthorized("Invalid email or password");
    }
    User user = userRepository.findByEmail(email.strip().toLowerCase())
        // Deliberately the SAME message as a wrong password below — never
        // reveal whether the email itself exists.
        .orElseThrow(() -> AuthException.unauthorized("Invalid email or password"));
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw AuthException.unauthorized("Invalid email or password");
    }
    if (!user.isEmailVerified()) {
      throw new ValidationException("Email not verified — check your inbox for the verification code");
    }
    return jwtIssuer.issueUserToken(user.getId().toString(), user.getEmail(), DEFAULT_ROLES);
  }

  /**
   * Updates the caller's own name and/or password — the CURRENT password
   * must check out first, regardless of which field is being changed;
   * there's no "forgot password" bypass here, only an authenticated user
   * proving they still are who they say before anything changes. Either
   * {@code newName} or {@code newPassword} may be null/blank to leave that
   * field untouched, but at least one must be provided.
   */
  public User changeDetails(UUID userId, String currentPassword, String newName, String newPassword) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> AuthException.unauthorized("Invalid credentials"));
    if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw AuthException.unauthorized("Current password is incorrect");
    }
    boolean changingName = newName != null && !newName.isBlank();
    boolean changingPassword = newPassword != null && !newPassword.isBlank();
    if (!changingName && !changingPassword) {
      throw new ValidationException("Provide a new name and/or a new password");
    }
    Instant now = Instant.now();
    if (changingName) {
      user.changeName(newName.strip(), now);
    }
    if (changingPassword) {
      validatePassword(newPassword);
      user.changePassword(passwordEncoder.encode(newPassword), now);
    }
    return userRepository.save(user);
  }
}
