package com.lynx.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Ensures every request has a correlation id and makes it available for logging
 * and downstream propagation.
 *
 * <p>Per request:
 * <ol>
 *   <li>reuse the inbound {@code X-Correlation-ID} header if present (the id started
 *       at the edge and flows through every hop), else generate a fresh one;
 *   <li>put it in the SLF4J MDC so all logs for this request carry it;
 *   <li>echo it on the response so the caller can report it for support;
 *   <li>always clear the MDC afterwards — thread-pooled request threads are reused,
 *       so a leftover id would bleed into the next, unrelated request.
 * </ol>
 *
 * <p>Extends {@link HttpFilter} so it runs exactly once per request and only for
 * HTTP. Register it early in the chain (before auth) so even rejected requests are
 * traceable.
 */
public class CorrelationIdFilter extends HttpFilter {

  @Override
  protected void doFilter(HttpServletRequest request, HttpServletResponse response,
                          FilterChain chain) throws IOException, ServletException {
    String correlationId = request.getHeader(SecurityHeaders.CORRELATION_ID);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = CorrelationId.generate();
    }
    CorrelationId.set(correlationId);
    response.setHeader(SecurityHeaders.CORRELATION_ID, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      // Reused pool threads: never leak this id into the next request.
      CorrelationId.clear();
    }
  }
}
