package com.lynx.account.service;

import com.lynx.account.domain.SavedRecipient;
import com.lynx.account.repository.SavedRecipientRepository;
import com.lynx.common.error.ConflictException;
import com.lynx.common.error.NotFoundException;
import com.lynx.common.error.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user's own transfer address book — server-side and per-user, replacing
 * an earlier client-side/localStorage version (see the {@code
 * saved_recipients} migration's own comment for why that was rejected).
 *
 * <p>Two collision rules, enforced here first (a friendly error) AND by the
 * table's own two UNIQUE constraints (the real guarantee under a race,
 * same pattern as {@link AccountService#createAccount} vs. {@code
 * accounts}' own {@code UNIQUE(user_id, currency)}):
 * <ol>
 *   <li>Saving the SAME {@code recipientUserId} again is an UPDATE (new
 *       label), never a second row.</li>
 *   <li>A DIFFERENT {@code recipientUserId} can never claim a label
 *       (case-insensitively) already used by this same user — refused,
 *       never silently overwritten, since silently reassigning a trusted
 *       label to a different person risks sending money to the wrong
 *       recipient later.</li>
 * </ol>
 */
public class RecipientService {

  private final SavedRecipientRepository repository;

  public RecipientService(SavedRecipientRepository repository) {
    this.repository = repository;
  }

  public List<SavedRecipient> list(String userId) {
    return repository.findByUserIdOrderByLabel(userId);
  }

  public SavedRecipient save(String userId, String label, String recipientUserId) {
    if (label == null || label.isBlank()) {
      throw new ValidationException("label is required");
    }
    if (recipientUserId == null || recipientUserId.isBlank()) {
      throw new ValidationException("recipientUserId is required");
    }
    String trimmedLabel = label.strip();

    repository.findByUserIdAndLabelIgnoreCase(userId, trimmedLabel)
        .filter(existing -> !existing.getRecipientUserId().equals(recipientUserId))
        .ifPresent(existing -> {
          throw new ConflictException(
              "\"" + trimmedLabel + "\" is already used for a different recipient — pick another label");
        });

    return repository.findByUserIdAndRecipientUserId(userId, recipientUserId)
        .map(existing -> {
          existing.relabel(trimmedLabel, Instant.now());
          return repository.save(existing);
        })
        .orElseGet(() -> {
          try {
            return repository.save(
                new SavedRecipient(UUID.randomUUID(), userId, recipientUserId, trimmedLabel, Instant.now()));
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The race the pre-checks above can't fully close: two concurrent
            // saves for the same (userId, label) or (userId, recipientUserId)
            // both passed the check before either committed — the table's
            // own UNIQUE constraints are the real guarantee; this just gives
            // it the same friendly shape as the common (non-racing) case.
            throw new ConflictException(
                "\"" + trimmedLabel + "\" is already used for a different recipient — pick another label");
          }
        });
  }

  public void remove(String userId, UUID id) {
    SavedRecipient recipient = repository.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new NotFoundException("Saved recipient not found: " + id));
    repository.delete(recipient);
  }
}
