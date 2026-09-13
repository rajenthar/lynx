package com.lynx.auth.api;

import com.lynx.auth.security.SigningKey;
import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes this instance's public key — exactly the URL every other Lynx
 * service's {@code application.yml} already hard-codes as {@code
 * lynx.security.jwks-url}, and what {@code JwtVerifier.fromJwksUrl}'s
 * {@code JWKSourceBuilder} fetches (and caches) to verify a token's
 * signature. Only the public half is ever exposed — {@link
 * SigningKey#publicJwk()}, never {@link SigningKey#signingKey()}.
 */
@RestController
@RequestMapping("/auth")
public class JwksController {

  private final SigningKey signingKey;

  public JwksController(SigningKey signingKey) {
    this.signingKey = signingKey;
  }

  @GetMapping("/.well-known/jwks.json")
  public Map<String, Object> jwks() {
    return new JWKSet(signingKey.publicJwk()).toJSONObject();
  }
}
