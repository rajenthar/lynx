package com.lynx.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single OTP code sent to a user's email, either at registration or via a
 * resend. Consumed exactly once — {@link #consumedAt} is set the moment a
 * matching code is verified, so the SAME code can never be replayed even if
 * it hasn't expired yet.
 */
@Entity
@Table(name = "email_otps")
public class EmailOtp {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String code;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected EmailOtp() {
    // JPA
  }

  public EmailOtp(UUID id, UUID userId, String code, Instant expiresAt, Instant now) {
    this.id = id;
    this.userId = userId;
    this.code = code;
    this.expiresAt = expiresAt;
    this.createdAt = now;
  }

  public boolean isUsable(String candidateCode, Instant now) {
    return consumedAt == null && expiresAt.isAfter(now) && code.equals(candidateCode);
  }

  public void markConsumed(Instant now) {
    this.consumedAt = now;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }
}
