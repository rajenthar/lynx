package com.lynx.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A registered internal caller allowed to request a service-identity token
 * via the OAuth2 Client Credentials grant (other-docs/11 Decision 3;
 * ADR-007 Option C). Stored in Postgres rather than static config so a new
 * caller can be registered without a redeploy, and so the secret is never
 * held in plaintext anywhere, including this service's own database.
 *
 * <p>{@code clientId} doubles as the token's {@code sub} claim — unlike a
 * {@link User}, a service client's "identity" IS its client id, there's no
 * separate generated id.
 */
@Entity
@Table(name = "service_clients")
public class ServiceClient {

  @Id
  @Column(name = "client_id")
  private String clientId;

  @Column(name = "client_secret_hash", nullable = false)
  private String clientSecretHash;

  /**
   * Roles granted to a token minted for this client — e.g.
   * {@code internal-service}, the exact claim {@code LedgerController#userId}
   * (ADR-007) already checks for. Native Postgres {@code TEXT[]}, Hibernate
   * 6's built-in array mapping — no extra library needed.
   */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "roles", nullable = false)
  private String[] roles;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ServiceClient() {
    // JPA
  }

  public ServiceClient(String clientId, String clientSecretHash, String[] roles, Instant createdAt) {
    this.clientId = clientId;
    this.clientSecretHash = clientSecretHash;
    this.roles = roles;
    this.createdAt = createdAt;
  }

  public String getClientId() {
    return clientId;
  }

  public String getClientSecretHash() {
    return clientSecretHash;
  }

  public String[] getRoles() {
    return roles;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
