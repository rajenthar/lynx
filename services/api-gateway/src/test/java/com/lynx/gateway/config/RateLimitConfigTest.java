package com.lynx.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

/**
 * {@link RateLimitConfig}'s {@code KeyResolver} — the two branches
 * (decodable {@code sub} vs. no usable token), proven directly rather
 * than only through the full gateway.
 */
class RateLimitConfigTest {

  private final RateLimitConfig config = new RateLimitConfig();

  private static String fakeJwt(String sub) {
    String header = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
    String payload = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
    // Signature doesn't need to be real — this resolver never verifies it.
    return header + "." + payload + ".fake-signature";
  }

  @Test
  void resolvesToTheJwtsSubjectWhenABearerTokenIsPresent() {
    ServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/v1/accounts")
            .header("Authorization", "Bearer " + fakeJwt("user-1")));

    String key = config.rateLimitKeyResolver().resolve(exchange).block();

    assertThat(key).isEqualTo("user:user-1");
  }

  @Test
  void fallsBackToIpWhenThereIsNoBearerTokenAtAll() {
    MockServerHttpRequest request = MockServerHttpRequest.post("/auth/register").build();
    ServerWebExchange withRemoteAddress = exchangeWithRemoteAddress(request, "203.0.113.5", 51000);

    String key = config.rateLimitKeyResolver().resolve(withRemoteAddress).block();

    assertThat(key).isEqualTo("ip:203.0.113.5");
  }

  @Test
  void fallsBackToIpWhenTheBearerTokenIsMalformed() {
    MockServerHttpRequest request = MockServerHttpRequest.get("/v1/accounts")
        .header("Authorization", "Bearer not-a-real-jwt")
        .build();
    ServerWebExchange withRemoteAddress = exchangeWithRemoteAddress(request, "198.51.100.7", 51000);

    String key = config.rateLimitKeyResolver().resolve(withRemoteAddress).block();

    assertThat(key).isEqualTo("ip:198.51.100.7");
  }

  private static ServerWebExchange exchangeWithRemoteAddress(
      MockServerHttpRequest request, String host, int port) {
    MockServerHttpRequest withRemote = MockServerHttpRequest.method(request.getMethod(), request.getURI())
        .headers(request.getHeaders())
        .remoteAddress(InetSocketAddress.createUnresolved(host, port))
        .build();
    return MockServerWebExchange.from(withRemote);
  }
}
