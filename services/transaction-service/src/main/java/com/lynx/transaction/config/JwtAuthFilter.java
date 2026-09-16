package com.lynx.transaction.config;

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
 * Same shape every other service's {@code JwtAuthFilter} already uses
 * (account-service's own copy, most recently) — verifies the caller's JWT
 * independently and stashes the resulting {@link UserContext} as a request
 * attribute. This service is end-user-JWT-only:
 * every request reaching {@code TransactionController} is a real end
 * user's own token, never an internal-service one — unlike {@code
 * ledger-service}, there is no dual trust boundary here at all.
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
