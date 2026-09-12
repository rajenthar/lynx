package com.lynx.orchestrator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lynx.common.error.ApiError;
import com.lynx.common.error.AuthException;
import com.lynx.security.CorrelationId;
import com.lynx.security.JwtVerifier;
import com.lynx.security.SecurityHeaders;
import com.lynx.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Same shape as {@code ledger-service}'s own {@code JwtAuthFilter} —
 * per [[jwt-auth-filter-placement]] (kept per-service, not in
 * lynx-security). Verifies every caller's JWT identically; the actual
 * "must be an internal-service caller" check happens one layer up, in
 * {@link com.lynx.orchestrator.api.SagaController}, since this filter never
 * looks at roles or the request body.
 */
public class JwtAuthFilter extends HttpFilter {

  private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

  public static final String USER_CONTEXT_ATTRIBUTE = "lynx.userContext";

  private static final String ACTUATOR_PATH_PREFIX = "/actuator/";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final JwtVerifier jwtVerifier;

  public JwtAuthFilter(JwtVerifier jwtVerifier) {
    this.jwtVerifier = jwtVerifier;
  }

  @Override
  protected void doFilter(HttpServletRequest request, HttpServletResponse response,
                           FilterChain chain) throws IOException, ServletException {
    if (request.getRequestURI().startsWith(ACTUATOR_PATH_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      String header = request.getHeader(SecurityHeaders.AUTHORIZATION);
      if (header == null || !header.startsWith(SecurityHeaders.BEARER_PREFIX)) {
        throw AuthException.unauthorized("Missing Authorization: Bearer <jwt> header");
      }
      String token = header.substring(SecurityHeaders.BEARER_PREFIX.length());
      UserContext userContext = jwtVerifier.verify(token);
      request.setAttribute(USER_CONTEXT_ATTRIBUTE, userContext);
    } catch (AuthException e) {
      log.warn("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
      writeError(response, e);
      return;
    }
    chain.doFilter(request, response);
  }

  private static void writeError(HttpServletResponse response, AuthException e) throws IOException {
    ApiError body = ApiError.of(e, CorrelationId.current());
    response.setStatus(e.code().httpStatus());
    response.setContentType("application/json");
    response.getWriter().write(MAPPER.writeValueAsString(body));
  }
}
