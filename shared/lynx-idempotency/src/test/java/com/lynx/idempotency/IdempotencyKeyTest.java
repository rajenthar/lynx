package com.lynx.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynx.common.error.ValidationException;

import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

  @Test
  void acceptsValidUuid() {
    IdempotencyKey key = IdempotencyKey.of("550e8400-e29b-41d4-a716-446655440000");
    assertEquals("550e8400-e29b-41d4-a716-446655440000", key.value());
  }

  @Test
  void rejectsMissingKey() {
    assertThrows(ValidationException.class, () -> IdempotencyKey.of(null));
    assertThrows(ValidationException.class, () -> IdempotencyKey.of("  "));
  }

  @Test
  void rejectsNonUuid() {
    assertThrows(ValidationException.class, () -> IdempotencyKey.of("not-a-uuid"));
  }
}
