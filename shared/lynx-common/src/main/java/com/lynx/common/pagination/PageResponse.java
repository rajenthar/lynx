package com.lynx.common.pagination;

import java.util.List;

/**
 * Standard page envelope returned by every list endpoint.
 *
 * @param <T> item type
 */
public record PageResponse<T>(
    List<T> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {

  public PageResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public static <T> PageResponse<T> of(List<T> items, PageRequest request, long totalElements) {
    int totalPages = (int) Math.ceil((double) totalElements / request.size());
    return new PageResponse<>(items, request.page(), request.size(), totalElements, totalPages);
  }

  public boolean hasNext() {
    return page + 1 < totalPages;
  }
}
