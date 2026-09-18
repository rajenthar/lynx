package com.lynx.auth.client;

import com.lynx.auth.service.JwtIssuer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Calls account-service's {@code /internal/accounts/seed-default} right
 * after a user completes OTP verification — every new user gets a starter
 * SGD account instead of an empty dashboard.
 *
 * <p>Unlike every OTHER internal-service caller in this project (see
 * {@code LedgerServiceClient} in account-service, or {@code
 * saga-orchestrator}'s own downstream clients), this one does NOT use
 * {@code ServiceTokenProvider} to fetch a token over HTTP from {@code
 * /auth/token} — auth-service already has direct, in-process access to
 * {@link JwtIssuer}, the exact thing that endpoint calls internally to
 * mint a token in the first place. Round-tripping through its own HTTP
 * endpoint to get a token it could sign itself would be pure overhead.
 *
 * <p>Deliberately best-effort, matching {@code OtpService.issueAndSend}'s
 * own reasoning for the email send: a transient failure here (account-service
 * down, a network blip) must never fail registration/verification itself —
 * the user can always create an account manually afterward, and this is a
 * convenience, not a step the rest of the system depends on.
 */
public class AccountServiceClient {

  private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

  private static final String SERVICE_CLIENT_ID = "auth-service";
  private static final List<String> SERVICE_ROLES = List.of("internal-service");

  private final RestClient restClient;
  private final JwtIssuer jwtIssuer;

  public AccountServiceClient(RestClient restClient, JwtIssuer jwtIssuer) {
    this.restClient = restClient;
    this.jwtIssuer = jwtIssuer;
  }

  public void seedDefaultAccount(UUID userId) {
    try {
      String token = jwtIssuer.issueServiceToken(SERVICE_CLIENT_ID, SERVICE_ROLES).value();
      restClient.post()
          .uri("/internal/accounts/seed-default")
          .header("Authorization", "Bearer " + token)
          .body(Map.of("userId", userId.toString()))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("Failed to seed a default account for userId={} — the user can still create one manually",
          userId, e);
    }
  }
}
