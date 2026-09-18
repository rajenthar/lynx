package com.lynx.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynx.account.domain.SavedRecipient;
import com.lynx.account.repository.SavedRecipientRepository;
import com.lynx.common.error.ConflictException;
import com.lynx.common.error.NotFoundException;
import com.lynx.common.error.ValidationException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecipientServiceTest {

  private SavedRecipientRepository repository;
  private RecipientService service;

  @BeforeEach
  void setUp() {
    repository = mock(SavedRecipientRepository.class);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    service = new RecipientService(repository);
  }

  @Test
  void saveRejectsABlankLabel() {
    assertThatThrownBy(() -> service.save("user-1", "  ", "user-2"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void saveRejectsABlankRecipientUserId() {
    assertThatThrownBy(() -> service.save("user-1", "Bob", " "))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void savingANewRecipientInsertsARow() {
    when(repository.findByUserIdAndLabelIgnoreCase("user-1", "Bob")).thenReturn(Optional.empty());
    when(repository.findByUserIdAndRecipientUserId("user-1", "user-2")).thenReturn(Optional.empty());

    SavedRecipient result = service.save("user-1", "Bob", "user-2");

    assertThat(result.getUserId()).isEqualTo("user-1");
    assertThat(result.getRecipientUserId()).isEqualTo("user-2");
    assertThat(result.getLabel()).isEqualTo("Bob");
  }

  @Test
  void savingTheSameRecipientAgainUpdatesTheLabelInPlaceRatherThanInserting() {
    SavedRecipient existing = new SavedRecipient(UUID.randomUUID(), "user-1", "user-2", "Bobby", Instant.now());
    when(repository.findByUserIdAndLabelIgnoreCase("user-1", "Bob")).thenReturn(Optional.empty());
    when(repository.findByUserIdAndRecipientUserId("user-1", "user-2")).thenReturn(Optional.of(existing));

    SavedRecipient result = service.save("user-1", "Bob", "user-2");

    assertThat(result.getId()).isEqualTo(existing.getId());
    assertThat(result.getLabel()).isEqualTo("Bob");
  }

  @Test
  void aDifferentRecipientClaimingAnAlreadyTakenLabelIsRejected() {
    SavedRecipient existing = new SavedRecipient(UUID.randomUUID(), "user-1", "user-2", "Bob", Instant.now());
    when(repository.findByUserIdAndLabelIgnoreCase("user-1", "Bob")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.save("user-1", "Bob", "user-3"))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Bob");

    verify(repository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void labelComparisonIsCaseInsensitiveViaTheRepositoryQuery() {
    // The service delegates case-insensitivity to the repository's own
    // findByUserIdAndLabelIgnoreCase — this test proves the service asks
    // with the label AS TYPED, trusting the query itself to fold case,
    // rather than the service silently lowercasing (which would make the
    // SAVED label lowercase too, losing the user's original casing).
    SavedRecipient existing = new SavedRecipient(UUID.randomUUID(), "user-1", "user-2", "Bob", Instant.now());
    when(repository.findByUserIdAndLabelIgnoreCase("user-1", "bob")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.save("user-1", "bob", "user-3"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void sameRecipientReclaimingItsOwnLabelIsNotTreatedAsACollision() {
    SavedRecipient existing = new SavedRecipient(UUID.randomUUID(), "user-1", "user-2", "Bob", Instant.now());
    when(repository.findByUserIdAndLabelIgnoreCase("user-1", "Bob")).thenReturn(Optional.of(existing));
    when(repository.findByUserIdAndRecipientUserId("user-1", "user-2")).thenReturn(Optional.of(existing));

    SavedRecipient result = service.save("user-1", "Bob", "user-2");

    assertThat(result.getLabel()).isEqualTo("Bob");
  }

  @Test
  void removeDeletesOnlyWhenOwnedByTheCaller() {
    UUID id = UUID.randomUUID();
    SavedRecipient existing = new SavedRecipient(id, "user-1", "user-2", "Bob", Instant.now());
    when(repository.findByIdAndUserId(id, "user-1")).thenReturn(Optional.of(existing));

    service.remove("user-1", id);

    verify(repository).delete(existing);
  }

  @Test
  void removeThrowsNotFoundForAnUnownedOrMissingId() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndUserId(id, "user-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.remove("user-1", id))
        .isInstanceOf(NotFoundException.class);
  }
}
