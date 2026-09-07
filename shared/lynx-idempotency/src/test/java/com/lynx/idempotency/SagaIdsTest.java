package com.lynx.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class SagaIdsTest {

  private static final IdempotencyKey KEY_A =
      IdempotencyKey.derivedFrom("550e8400-e29b-41d4-a716-446655440000");
  private static final IdempotencyKey KEY_B =
      IdempotencyKey.derivedFrom("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

  @Test
  void sameInputsAlwaysDeriveSameSagaId() {
    // The retry-safety property: a retry re-derives the ORIGINAL saga_id,
    // so the DB UNIQUE constraint can catch the duplicate.
    UUID first = SagaIds.deriveSagaId("user-123", KEY_A);
    UUID second = SagaIds.deriveSagaId("user-123", KEY_A);
    assertEquals(first, second);
  }

  @Test
  void differentUsersSameKeyDeriveDifferentSagaIds() {
    // Two clients accidentally using the same UUID must not collide.
    UUID userA = SagaIds.deriveSagaId("user-123", KEY_A);
    UUID userB = SagaIds.deriveSagaId("user-456", KEY_A);
    assertNotEquals(userA, userB);
  }

  @Test
  void sameUserDifferentKeysDeriveDifferentSagaIds() {
    UUID first = SagaIds.deriveSagaId("user-123", KEY_A);
    UUID second = SagaIds.deriveSagaId("user-123", KEY_B);
    assertNotEquals(first, second);
  }
}
