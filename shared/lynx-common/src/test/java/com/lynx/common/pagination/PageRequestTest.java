package com.lynx.common.pagination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynx.common.error.ValidationException;
import org.junit.jupiter.api.Test;

class PageRequestTest {

  @Test
  void validRequestComputesOffset() {
    PageRequest request = PageRequest.of(3, 20);
    assertEquals(60, request.offset());
  }

  @Test
  void firstPageUsesDefaults() {
    PageRequest request = PageRequest.first();
    assertEquals(0, request.page());
    assertEquals(PageRequest.DEFAULT_SIZE, request.size());
  }

  @Test
  void negativePageRejected() {
    assertThrows(ValidationException.class, () -> PageRequest.of(-1, 20));
  }

  @Test
  void zeroSizeRejected() {
    assertThrows(ValidationException.class, () -> PageRequest.of(0, 0));
  }

  @Test
  void sizeAboveMaxRejected() {
    assertThrows(ValidationException.class, () -> PageRequest.of(0, PageRequest.MAX_SIZE + 1));
  }

  @Test
  void sizeAtMaxAllowed() {
    PageRequest request = PageRequest.of(0, PageRequest.MAX_SIZE);
    assertEquals(PageRequest.MAX_SIZE, request.size());
  }
}
