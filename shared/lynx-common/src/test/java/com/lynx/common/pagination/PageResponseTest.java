package com.lynx.common.pagination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

  @Test
  void computesTotalPages() {
    PageResponse<String> response =
        PageResponse.of(List.of("a", "b"), PageRequest.of(0, 2), 5);
    assertEquals(3, response.totalPages()); // ceil(5 / 2)
    assertEquals(5, response.totalElements());
  }

  @Test
  void hasNextOnEarlierPages() {
    PageResponse<String> response =
        PageResponse.of(List.of("a", "b"), PageRequest.of(0, 2), 5);
    assertTrue(response.hasNext());
  }

  @Test
  void noNextOnLastPage() {
    PageResponse<String> response =
        PageResponse.of(List.of("e"), PageRequest.of(2, 2), 5);
    assertFalse(response.hasNext());
  }

  @Test
  void nullItemsBecomeEmptyList() {
    PageResponse<String> response = new PageResponse<>(null, 0, 20, 0, 0);
    assertEquals(List.of(), response.items());
  }
}
