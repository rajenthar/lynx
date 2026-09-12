package com.lynx.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Shared plumbing for {@link LedgerServiceClient} and
 * {@link FxRateServiceClient} — obtaining the service token (ADR-007),
 * one 401-triggered {@link ServiceTokenProvider#invalidate()} + retry (see
 * service-token-provider-flows.html's "Reactive invalidation"), a
 * per-client resilience4j {@link CircuitBreaker} guarding the actual
 * downstream call, and classifying every failure into exactly one of the
 * two shapes {@code SagaOrchestratorService} knows how to react to:
 * {@link DownstreamUnavailableException} (transient — leave the saga for
 * retry) or {@link SagaStepFailedException} (a real rejection — compensate
 * and fail the saga). Per other-docs/10's confirmed decision, there is no
 * third case.
 *
 * <p><b>Three resilience4j {@code CircuitBreaker} instances exist across
 * this call path, not one shared breaker</b> (other-docs/10 Decision 8):
 * {@link ServiceTokenProvider}'s own internal one guards only the shared
 * token-endpoint fetch (unchanged since {@code lynx-security}'s own
 * hand-rolled {@code CircuitBreaker} was retired — same library
 * everywhere now, just a separate NAMED instance per guarded call, each
 * with its own failure count and open/closed state). THIS class's
 * breaker — one per client, built in {@code BeansConfig} — guards the
 * actual downstream calls to {@code ledger-service}/{@code
 * fx-rate-service}, configured with {@code ignoreExceptions(...)} so a
 * 4xx doesn't count as a breaker failure but still throws normally to the
 * catch blocks below; {@code ServiceTokenProvider}'s own breaker doesn't
 * need that config, since a non-200 token response is always genuinely a
 * failure, no 4xx-vs-5xx distinction to make there.
 */
abstract class AbstractServiceClient {

  private static final Logger log = LoggerFactory.getLogger(AbstractServiceClient.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ServiceTokenProvider tokenProvider;
  private final CircuitBreaker circuitBreaker;

  protected AbstractServiceClient(ServiceTokenProvider tokenProvider, CircuitBreaker circuitBreaker) {
    this.tokenProvider = tokenProvider;
    this.circuitBreaker = circuitBreaker;
  }

  /**
   * @param request given the current bearer token, performs the call and
   *     returns the parsed response body (may be {@code null} for an
   *     endpoint whose response this service doesn't need to read — e.g.
   *     {@code ledger-service}'s {@code hold}/{@code lock}/{@code settle}/
   *     {@code release}, where only success/failure matters)
   */
  protected <T> T call(Function<String, ResponseEntity<T>> request) {
    String token;
    try {
      token = tokenProvider.currentToken();
    } catch (CallNotPermittedException | ServiceTokenException e) {
      throw new DownstreamUnavailableException("Could not obtain a service token: " + e.getMessage(), e);
    }
    return callWithToken(request, token, true);
  }

  private <T> T callWithToken(Function<String, ResponseEntity<T>> request, String token, boolean allowRetry) {
    try {
      // No manual failure-classification wrapper needed here — the
      // CircuitBreakerConfig this instance was built with (BeansConfig)
      // sets ignoreExceptions(HttpClientErrorException.class), so a 4xx
      // (including 401) still propagates to the catch blocks below
      // completely normally, it just isn't counted as a breaker failure.
      // Only a genuine HttpServerErrorException/ResourceAccessException
      // increments the breaker's failure count.
      return circuitBreaker.executeSupplier(() -> request.apply(token)).getBody();
    } catch (CallNotPermittedException e) {
      throw new DownstreamUnavailableException(
          "Circuit open for this downstream service: " + e.getMessage(), e);
    } catch (HttpClientErrorException.Unauthorized e) {
      if (!allowRetry) {
        throw new DownstreamUnavailableException(
            "Still unauthorized after invalidating and refetching the service token", e);
      }
      log.info("Downstream call returned 401 — invalidating cached service token and retrying once");
      tokenProvider.invalidate();
      String freshToken;
      try {
        freshToken = tokenProvider.currentToken();
      } catch (CallNotPermittedException | ServiceTokenException fetchFailed) {
        throw new DownstreamUnavailableException(
            "Could not refetch a service token after a 401: " + fetchFailed.getMessage(), fetchFailed);
      }
      return callWithToken(request, freshToken, false);
    } catch (HttpClientErrorException e) {
      // A genuine 4xx (other than 401, handled above) — insufficient
      // funds, unsupported currency, a business rule the downstream
      // service rejected outright. Never transient; see SagaStepFailedException.
      throw new SagaStepFailedException(extractMessage(e));
    } catch (HttpServerErrorException | ResourceAccessException e) {
      // 5xx, or unreachable entirely (connection refused, timeout, DNS).
      throw new DownstreamUnavailableException("Downstream call failed: " + e.getMessage(), e);
    }
  }

  /** Best-effort: pulls {@code ApiError.message} out of the response body; falls back to the raw exception message. */
  private static String extractMessage(HttpClientErrorException e) {
    try {
      JsonNode body = MAPPER.readTree(e.getResponseBodyAsString());
      JsonNode message = body.get("message");
      if (message != null) {
        return message.asText();
      }
    } catch (Exception parseFailed) {
      // Body wasn't the expected ApiError shape — fall through to the raw message below.
    }
    return e.getMessage();
  }
}
