package com.lynx.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A registered end user (other-docs/11 Decision 1) — email + BCrypt password
 * hash + a display name, nothing more. No profile, no phone, no email
 * verification — this is a login, not an account/profile service.
 *
 * <p>{@code id} is this user's {@code userId} everywhere else in Lynx — the
 * same value {@link com.lynx.auth.service.JwtIssuer} puts in a minted
 * token's {@code sub} claim, which every other service's {@code JwtVerifier}
 * then treats as the stable identity used for saga_id derivation (ADR-004).
 */
@Entity
@Table(name = "users")
public class User {

  @Id
  private UUID id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(nullable = false)
  private String name;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected User() {
    // JPA
  }

  public User(UUID id, String email, String name, String passwordHash, Instant now) {
    this.id = id;
    this.email = email;
    this.name = name;
    this.passwordHash = passwordHash;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getName() {
    return name;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
