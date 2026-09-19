package com.lynx.transaction.client;

import com.lynx.security.ServiceTokenException;
import com.lynx.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Shared plumbing for {@link SagaOrchestratorClient} and {@link
 * AccountServiceClient} — same shape as {@code saga-orchestrator}'s own
 * {@code AbstractServiceClient} (copied, not shared as a library, same
 * reasoning as elsewhere in this project). Every call goes out under THIS
 * service's own service-identity token (ADR-007) — never a forwarded
 * end-user token, the same trust model every other internal caller in
 * this system already uses. Classifies every failure into {@link
 * DownstreamUnavailableException} (transient — the client should retry
 * the whole request) or {@link DownstreamRejectedException} (a real 4xx,
 * carrying its status code so the caller can react differently depending
 * on which one — unlike {@code saga-orchestrator}, where every 4xx means
 * the same thing: compensate and fail).
 */
abstract class AbstractServiceClient {

  private static final Logger log = LoggerFactory.getLogger(AbstractServiceClient.class);

  private final ServiceTokenProvider tokenProvider;
  private final CircuitBreaker circuitBreaker;

  protected AbstractServiceClient(ServiceTokenProvider tokenProvider, CircuitBreaker circuitBreaker) {
    this.tokenProvider = tokenProvider;
    this.circuitBreaker = circuitBreaker;
  }

  /** Fetches this service's OWN service-identity token (ADR-007) before calling. */
  protected <T> T call(Function<String, ResponseEntity<T>> request) {
    String token;
    try {
      token = tokenProvider.currentToken();
    } catch (CallNotPermittedException | ServiceTokenException e) {
      log.error("Could not obtain a service token", e);
      throw new DownstreamUnavailableException("A downstream service is temporarily unavailable — please retry", e);
    }
    return callWithToken(request, token, true);
  }

  private <T> T callWithToken(Function<String, ResponseEntity<T>> request, String token, boolean allowRetry) {
    try {
      return circuitBreaker.executeSupplier(() -> request.apply(token)).getBody();
    } catch (CallNotPermittedException e) {
      throw new DownstreamUnavailableException(
          "A downstream service is temporarily unavailable — please retry", e);
    } catch (HttpClientErrorException.Unauthorized e) {
      if (!allowRetry) {
        throw new DownstreamUnavailableException(
            "A downstream service is temporarily unavailable — please retry", e);
      }
      log.info("Downstream call returned 401 — invalidating cached service token and retrying once");
      tokenProvider.invalidate();
      String freshToken;
      try {
        freshToken = tokenProvider.currentToken();
      } catch (CallNotPermittedException | ServiceTokenException fetchFailed) {
        log.error("Could not refetch a service token after a 401", fetchFailed);
        throw new DownstreamUnavailableException(
            "A downstream service is temporarily unavailable — please retry", fetchFailed);
      }
      return callWithToken(request, freshToken, false);
    } catch (HttpClientErrorException e) {
      throw new DownstreamRejectedException(e.getStatusCode().value(), extractMessage(e));
    } catch (HttpServerErrorException | ResourceAccessException e) {
      log.warn("Downstream call failed", e);
      throw new DownstreamUnavailableException("A downstream service is temporarily unavailable — please retry", e);
    }
  }

  /** Best-effort: pulls {@code ApiError.message} out of the response body; falls back to the raw exception message. */
  private static String extractMessage(HttpClientErrorException e) {
    try {
      com.fasterxml.jackson.databind.JsonNode body =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString());
      com.fasterxml.jackson.databind.JsonNode message = body.get("message");
      if (message != null) {
        return message.asText();
      }
    } catch (Exception parseFailed) {
      // Body wasn't the expected ApiError shape — fall through to the raw message below.
    }
    return e.getMessage();
  }
}
