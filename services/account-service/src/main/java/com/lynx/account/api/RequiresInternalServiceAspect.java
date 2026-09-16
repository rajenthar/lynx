package com.lynx.account.api;

import com.lynx.account.config.JwtAuthFilter;
import com.lynx.common.error.AuthException;
import com.lynx.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Enforces {@link RequiresInternalService} — runs before any annotated
 * controller method, reading the exact same {@link UserContext} {@link
 * JwtAuthFilter} already stashed on the request. Same check {@code
 * InternalAccountController} used to make inline, moved out to a
 * cross-cutting concern so the check is visible right on the endpoint's
 * own declaration, not buried in its first line.
 */
@Aspect
@Component
class RequiresInternalServiceAspect {

  private static final Logger log = LoggerFactory.getLogger(RequiresInternalServiceAspect.class);

  private static final String INTERNAL_SERVICE_ROLE = "internal-service";

  @Before("@annotation(RequiresInternalService)")
  public void checkInternalServiceCaller() {
    HttpServletRequest request = currentRequest();
    UserContext userContext =
        (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    if (!userContext.hasRole(INTERNAL_SERVICE_ROLE)) {
      log.warn("Rejected non-internal-service caller {} for {}", userContext.userId(), request.getRequestURI());
      throw AuthException.forbidden("Only an internal-service caller may use this endpoint");
    }
  }

  private static HttpServletRequest currentRequest() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    return attrs.getRequest();
  }
}
