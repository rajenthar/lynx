package com.lynx.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a user's own transfer address book — "save this userId as
 * 'Bob'" so a future transfer doesn't require retyping a raw userId. Server-
 * side and per-user by design (see the migration's own comment for why a
 * client-side/localStorage version was tried first and rejected): this
 * survives a new device, a cleared browser, or a different session, and is
 * scoped to the ACTUAL logged-in user rather than to a browser origin.
 */
@Entity
@Table(name = "saved_recipients")
public class SavedRecipient {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "recipient_user_id", nullable = false)
  private String recipientUserId;

  @Column(nullable = false, length = 100)
  private String label;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SavedRecipient() {
    // JPA
  }

  public SavedRecipient(UUID id, String userId, String recipientUserId, String label, Instant now) {
    this.id = id;
    this.userId = userId;
    this.recipientUserId = recipientUserId;
    this.label = label;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void relabel(String label, Instant now) {
    this.label = label;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getRecipientUserId() {
    return recipientUserId;
  }

  public String getLabel() {
    return label;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
