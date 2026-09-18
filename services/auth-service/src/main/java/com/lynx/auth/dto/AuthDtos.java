package com.lynx.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request/response shapes for {@code /auth/register}, {@code /auth/login}, and {@code /auth/token}. */
public final class AuthDtos {

  public record RegisterRequest(String email, String password, String name) {
  }

  public record LoginRequest(String email, String password) {
  }

  public record VerifyOtpRequest(String email, String code) {
  }

  public record ResendOtpRequest(String email) {
  }

  /**
   * Both {@code newName} and {@code newPassword} are optional (leave one
   * null/blank to only change the other) but {@code currentPassword} is
   * always required — it's checked before anything is updated.
   */
  public record ChangeDetailsRequest(String currentPassword, String newName, String newPassword) {
  }

  /** Returned by {@code /auth/register} — no token yet, the account is unverified. */
  public record RegisterResponse(String userId, String email, String message) {
  }

  /** {@code /auth/me} — this service's own answer to "who am I", for the frontend's profile page. */
  public record UserProfileView(String userId, String email, String name, boolean emailVerified) {
  }

  /**
   * OAuth2's exact field naming (RFC 6749 §5.1) for both
   * {@code /auth/token}'s response and {@code /auth/login}/
   * {@code /auth/register}'s (same shape reused — a login IS just a token
   * issuance from the client's point of view). {@code snake_case} is
   * required, not a style choice: {@code ServiceTokenProvider.fetchToken}
   * parses {@code json.get("access_token")}/{@code json.get("expires_in")}
   * verbatim.
   */
  public record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("token_type") String tokenType,
      @JsonProperty("expires_in") long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresInSeconds) {
      return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
    }
  }

  private AuthDtos() {
  }
}
