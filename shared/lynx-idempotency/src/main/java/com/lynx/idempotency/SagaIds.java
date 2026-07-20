package com.lynx.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic saga_id derivation — the heart of ADR-004.
 *
 * <p>{@code saga_id = nameUUID(user_id + "|" + idempotency_key)}. Same inputs
 * ALWAYS produce the same saga_id, so a retry re-derives the id of the original
 * attempt and the ledger's UNIQUE constraint rejects the duplicate. A randomly
 * generated saga_id would make every retry look like a new transfer — the
 * system-breaking bug this class exists to prevent.
 *
 * <p>Why user_id is part of the derivation: two DIFFERENT users accidentally
 * sending the same Idempotency-Key UUID must not collide — including user_id
 * gives them different saga_ids, so neither blocks the other.
 */
public final class SagaIds {

  public static UUID deriveSagaId(String userId, IdempotencyKey key) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(key, "key");
    String composite = userId + "|" + key.value();
    return UUID.nameUUIDFromBytes(composite.getBytes(StandardCharsets.UTF_8));
  }

  private SagaIds() {
  }
}
