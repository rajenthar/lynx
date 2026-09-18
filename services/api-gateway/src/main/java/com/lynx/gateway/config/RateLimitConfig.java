package com.lynx.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Resolves what "one caller" means for {@code RequestRateLimiter}'s
 * per-key token buckets — per-user where an identity is
 * available, per-IP otherwise. This is the ONLY place this module ever
 * looks inside a JWT, and it deliberately does NOT verify it — the
 * gateway never authenticates a caller (that stays each downstream
 * service's own {@code JwtAuthFilter}'s job, unchanged); this only needs
 * a key to bucket requests under. A forged {@code sub} just gets its own
 * harmless bucket — it grants no privilege and is never trusted for an
 * authorization decision anywhere.
 */
@Configuration
public class RateLimitConfig {

  private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Bean
  public KeyResolver rateLimitKeyResolver() {
    return this::resolveKey;
  }

  private Mono<String> resolveKey(ServerWebExchange exchange) {
    String subject = extractSubjectOrNull(exchange.getRequest());
    if (subject != null) {
      return Mono.just("user:" + subject);
    }
    String ip = clientIpOrUnknown(exchange.getRequest());
    return Mono.just("ip:" + ip);
  }

  /**
   * Decodes (never verifies) a {@code Bearer} JWT's {@code sub} claim.
   * Returns {@code null} on ANY problem — missing header, malformed
   * token, missing claim — falling back to IP-keying rather than ever
   * failing the request over a rate-limit-keying detail.
   */
  private static String extractSubjectOrNull(ServerHttpRequest request) {
    String header = request.getHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      return null;
    }
    String token = header.substring("Bearer ".length());
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      return null;
    }
    try {
      byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
      JsonNode payload = MAPPER.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
      JsonNode sub = payload.get("sub");
      return sub == null ? null : sub.asText();
    } catch (Exception e) {
      log.debug("Could not decode a Bearer token's sub claim for rate-limit keying — falling back to IP", e);
      return null;
    }
  }

  /**
   * {@code getHostString()}, not {@code getAddress().getHostAddress()} —
   * the latter throws on an unresolved address (never actually reverse-
   * DNS-resolved, which a real inbound connection's remote address
   * usually isn't either); {@code getHostString()} returns a usable
   * string either way, resolved or not.
   */
  private static String clientIpOrUnknown(ServerHttpRequest request) {
    return request.getRemoteAddress() == null
        ? "unknown"
        : request.getRemoteAddress().getHostString();
  }
}
