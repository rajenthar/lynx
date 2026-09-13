package com.lynx.auth.security;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * This instance's RSA signing key, generated fresh at startup
 * (other-docs/11 Decision 4) — never persisted, never leaves this process.
 * The public half is published at {@code GET /auth/.well-known/jwks.json}
 * (see {@code JwksController}); the private half signs every token
 * {@link com.lynx.auth.service.JwtIssuer} mints.
 *
 * <p>A restart invalidates every previously-issued token. Accepted: every
 * verifying service already re-checks signature/issuer/audience/expiry per
 * request against the live JWKS (nimbus's {@code JWKSourceBuilder} caches
 * it, refetching on a verification failure/rotation), so nothing anywhere
 * depends on this key surviving a restart. Revisit (a persisted key, env
 * var or mounted file) only once real deployment is designed.
 */
public final class SigningKey {

  private final RSAKey rsaJwk;

  public SigningKey() {
    KeyPair keyPair;
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      keyPair = generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      // RSA is a JDK-mandatory algorithm — this can only happen on a
      // fundamentally broken JVM, not a runtime condition to recover from.
      throw new IllegalStateException("JVM does not support RSA key generation", e);
    }
    // A fresh key id per boot — lets a verifying service's cached JWKS
    // distinguish "this token was signed by the key I already have" from
    // "auth-service restarted, refetch the JWKS" (nimbus does this via kid
    // matching before falling back to a refetch).
    String keyId = UUID.randomUUID().toString();
    this.rsaJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
        .privateKey((RSAPrivateKey) keyPair.getPrivate())
        .keyID(keyId)
        .build();
  }

  /** The full key, including the private half — for signing only. */
  public RSAKey signingKey() {
    return rsaJwk;
  }

  /** The public half only, safe to publish — for the JWKS endpoint. */
  public RSAKey publicJwk() {
    return rsaJwk.toPublicJWK();
  }
}
