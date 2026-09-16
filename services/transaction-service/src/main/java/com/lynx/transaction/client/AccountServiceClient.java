package com.lynx.transaction.client;

import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.springframework.web.client.RestClient;

/**
 * The one call against {@code account-service} — both account ids a
 * transfer needs, resolved in a single round trip, under THIS service's
 * own service-identity token (ADR-007). Merged deliberately (raised
 * directly) from two separate calls this used to be: a sender-account
 * lookup and a recipient-account lookup, each with its own endpoint on
 * {@code account-service} but each existing only to serve this one
 * caller — splitting them bought no real reusability, only an extra round
 * trip and more code on both sides.
 *
 * <p>A {@code POST} with a body, not a {@code GET} with query params —
 * deliberately: keeps both user ids out of the request URL (and
 * therefore out of any access/proxy logs that capture URLs but not
 * bodies), unlike every other lookup in this system.
 */
public class AccountServiceClient extends AbstractServiceClient {

  private final RestClient restClient;

  public AccountServiceClient(RestClient restClient, ServiceTokenProvider tokenProvider,
                               CircuitBreaker circuitBreaker) {
    super(tokenProvider, circuitBreaker);
    this.restClient = restClient;
  }

  private record ResolveTransferRequest(
      String senderUserId, String senderCurrency, String recipientUserId, String recipientCurrency) {
  }

  public record ResolvedTransferAccounts(UUID senderAccountId, UUID recipientAccountId) {
  }

  public ResolvedTransferAccounts resolveTransferAccounts(
      String senderUserId, String senderCurrency, String recipientUserId, String recipientCurrency) {
    ResolveTransferRequest body =
        new ResolveTransferRequest(senderUserId, senderCurrency, recipientUserId, recipientCurrency);
    return call(token -> restClient.post()
        .uri("/internal/accounts/resolve-transfer")
        .header("Authorization", "Bearer " + token)
        .body(body)
        .retrieve()
        .toEntity(ResolvedTransferAccounts.class));
  }
}
