package com.lynx.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SagaMdcTest {

  @AfterEach
  void cleanUp() {
    SagaMdc.clear();
  }

  @Test
  void currentIsNullWhenUnset() {
    assertNull(SagaMdc.current());
  }

  @Test
  void setMakesCurrentVisible() {
    SagaMdc.set("saga-123");
    assertEquals("saga-123", SagaMdc.current());
  }

  @Test
  void clearRemovesIt() {
    SagaMdc.set("saga-123");
    SagaMdc.clear();
    assertNull(SagaMdc.current());
  }
}
