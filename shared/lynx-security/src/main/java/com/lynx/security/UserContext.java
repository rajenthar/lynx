package com.lynx.security;

import com.lynx.common.error.AuthException;
import java.util.Objects;
import java.util.Set;

/**
 * The authenticated caller's identity, extracted from a verified JWT.
 *
 * <p>Immutable and framework-free. Produced by {@link JwtVerifier#verify(String)}
 * after the token's signature, issuer, audience, and expiry have all been checked —
 * so a UserContext instance always represents a genuinely authenticated principal.
 *
 * @param userId the subject ({@code sub} claim) — a stable, unique user id. This is
 *               the same value used to derive saga_ids (ADR-004), so it must be
 *               consistent for the same user across requests.
 * @param email  the user's email ({@code email} claim), may be null if not present
 * @param roles  granted roles ({@code roles} claim) for authorization checks
 */
public record UserContext(String userId, String email, Set<String> roles) {

  public UserContext {
    Objects.requireNonNull(userId, "userId");
    // Defensive immutable copy; never null.
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  /**
   * Assert the caller has {@code role}, or fail with 403.
   *
   * <p>Distinct from authentication: reaching this method means we already know WHO
   * the caller is (they had a valid token); this checks whether they are PERMITTED.
   */
  public void requireRole(String role) {
    if (!hasRole(role)) {
      throw AuthException.forbidden(
          "User " + userId + " lacks required role: " + role);
    }
  }
}
