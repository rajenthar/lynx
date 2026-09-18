package com.lynx.account.dto;

import java.time.Instant;
import java.util.UUID;

public final class RecipientDtos {

  public record SaveRecipientRequest(String label, String recipientUserId) {
  }

  public record SavedRecipientView(UUID id, String label, String recipientUserId, Instant updatedAt) {
  }

  private RecipientDtos() {
  }
}
