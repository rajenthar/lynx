package com.lynx.ledger.api;

import com.lynx.common.error.AuthException;
import com.lynx.ledger.config.JwtAuthFilter;
import com.lynx.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-007 Option C's trust boundary, extracted so every controller in this
 * service enforces it identically — originally {@code LedgerController}'s
 * own private {@code userId(...)} helper, pulled out once
 * {@code LedgerDepositController} (other-docs/12) needed the exact same
 * logic: an ordinary end-user token's own {@code sub} claim is used as-is;
 * a token carrying the {@code internal-service} role MUST supply {@code
 * onBehalfOfUserId} (401 if it doesn't), and only such a caller may supply
 * one at all (403 for an ordinary end-user token that tries).
 */
final class TrustedCaller {

  private static final Logger log = LoggerFactory.getLogger(TrustedCaller.class);

  private static final String INTERNAL_SERVICE_ROLE = "internal-service";

  static String userId(HttpServletRequest request, String onBehalfOfUserId) {
    UserContext userContext =
        (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    boolean isServiceCaller = userContext.hasRole(INTERNAL_SERVICE_ROLE);
    if (isServiceCaller) {
      if (onBehalfOfUserId == null || onBehalfOfUserId.isBlank()) {
        log.warn("Internal-service caller {} omitted onBehalfOfUserId", userContext.userId());
        throw AuthException.unauthorized(
            "Internal-service callers must supply onBehalfOfUserId");
      }
      return onBehalfOfUserId;
    }
    if (onBehalfOfUserId != null) {
      log.warn("Non-service caller {} attempted to assert onBehalfOfUserId={}",
          userContext.userId(), onBehalfOfUserId);
      throw AuthException.forbidden(
          "Only an internal-service caller may assert onBehalfOfUserId");
    }
    return userContext.userId();
  }

  private TrustedCaller() {
  }
}
