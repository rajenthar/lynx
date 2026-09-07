package com.lynx.ledger.config;

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
 * Independently verifies the caller's JWT and stashes the resulting
 * {@link UserContext} as a request attribute — the defense-in-depth auth
 * model confirmed for ledger-service. This filter itself is unchanged by
 * ADR-007: it verifies every caller identically, end-user or internal
 * service alike, and never looks at the request body. Whether the
 * resulting {@code UserContext.userId()} is actually used, or overridden by
 * a request-body {@code onBehalfOfUserId} field (only for a token proven to
 * carry the {@code internal-service} role), is decided later, in the
 * controller — see {@code LedgerController#userId}.
 *
 * <p>Runs before {@code DispatcherServlet}, so a rejected token never reaches
 * {@code GlobalExceptionHandler} (a {@code @RestControllerAdvice} only
 * intercepts exceptions thrown from controller methods) — the
 * {@link ApiError} response is written here directly, in the exact same
 * shape, so clients see one uniform error format regardless of where the
 * rejection happened.
 *
 * <p>Skips {@code /actuator/**}: health checks (load balancers, container
 * orchestrators) and metrics scraping (Prometheus, via the OTel Collector)
 * must never require a caller's business JWT — requiring one would break
 * exactly the infrastructure that's supposed to observe this service
 * regardless of its business-auth state.
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
      // WARN, not ERROR: a rejected token is an expected, routine occurrence
      // (expired client token, malformed header) — never logged with a
      // stack trace, which would bury real errors under routine noise.
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
