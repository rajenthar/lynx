package com.lynx.account.repository;

import com.lynx.account.domain.SavedRecipient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SavedRecipientRepository extends JpaRepository<SavedRecipient, UUID> {

  List<SavedRecipient> findByUserIdOrderByLabel(String userId);

  Optional<SavedRecipient> findByUserIdAndRecipientUserId(String userId, String recipientUserId);

  /**
   * Case-insensitive — matches the functional UNIQUE index on {@code
   * (user_id, lower(label))}; a plain generated {@code findByUserIdAndLabel}
   * would be case-SENSITIVE and miss "Bob" vs. "bob" as the same collision.
   */
  @Query("SELECT r FROM SavedRecipient r WHERE r.userId = :userId AND lower(r.label) = lower(:label)")
  Optional<SavedRecipient> findByUserIdAndLabelIgnoreCase(@Param("userId") String userId, @Param("label") String label);

  Optional<SavedRecipient> findByIdAndUserId(UUID id, String userId);
}
