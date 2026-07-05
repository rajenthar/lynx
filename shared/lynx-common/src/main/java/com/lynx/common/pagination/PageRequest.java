package com.lynx.common.pagination;

import com.lynx.common.error.ValidationException;

/**
 * Page request with hard bounds.
 *
 * <p>{@code size} is capped at {@link #MAX_SIZE} so a client can never ask a
 * read model for an unbounded result set (same discipline as the
 * orchestrator's LIMIT batching — no query is allowed to drain a table).
 */
public record PageRequest(int page, int size) {

  public static final int MAX_SIZE = 100;
  public static final int DEFAULT_SIZE = 20;

  public PageRequest {
    if (page < 0) {
      throw new ValidationException("page must be >= 0, got: " + page);
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new ValidationException("size must be 1.." + MAX_SIZE + ", got: " + size);
    }
  }

  public static PageRequest first() {
    return new PageRequest(0, DEFAULT_SIZE);
  }

  public static PageRequest of(int page, int size) {
    return new PageRequest(page, size);
  }

  public int offset() {
    return page * size;
  }
}
