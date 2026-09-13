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
 * Registration and login (other-docs/11 Decision 1) — a real Postgres-backed
 * user, BCrypt-hashed password, no email verification/reset/lockout. Every
 * registered user gets the plain {@code user} role; there's no admin/elevated
 * role concept yet.
 */
public class UserAuthService {

  private static final List<String> DEFAULT_ROLES = List.of("user");
  private static final int MIN_PASSWORD_LENGTH = 8;

  private final UserRepository userRepository;
  private final BCryptPasswordEncoder passwordEncoder;
  private final JwtIssuer jwtIssuer;

  public UserAuthService(
      UserRepository userRepository, BCryptPasswordEncoder passwordEncoder, JwtIssuer jwtIssuer) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtIssuer = jwtIssuer;
  }

  /**
   * Creates the user, then immediately signs them in (returns the same
   * token shape {@link #login} does) — one fewer round trip for a new
   * caller than register-then-separately-login would require.
   */
  public JwtIssuer.IssuedToken register(String email, String password, String name) {
    if (email == null || email.isBlank()) {
      throw new ValidationException("email is required");
    }
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new ValidationException("password must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
    if (name == null || name.isBlank()) {
      throw new ValidationException("name is required");
    }
    String normalizedEmail = email.strip().toLowerCase();
    if (userRepository.existsByEmail(normalizedEmail)) {
      throw new ConflictException("An account with this email already exists");
    }
    User user = new User(
        UUID.randomUUID(), normalizedEmail, name.strip(),
        passwordEncoder.encode(password), Instant.now());
    userRepository.save(user);
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
    return jwtIssuer.issueUserToken(user.getId().toString(), user.getEmail(), DEFAULT_ROLES);
  }
}
