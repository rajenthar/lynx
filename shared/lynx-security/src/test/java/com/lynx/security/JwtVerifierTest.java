package com.lynx.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.lynx.common.error.AuthException;
import com.lynx.common.error.ErrorCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class JwtVerifierTest {

  private static final String ISSUER = "https://auth.lynx";
  private static final String AUDIENCE = "lynx-api";

  private static RSAKey signingKey; // auth-service's private key
  private static JwtVerifier verifier; // configured with the matching public key

  @BeforeAll
  static void setUp() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
    // Only the PUBLIC key goes into the JWKS the verifier trusts.
    JWKSource<SecurityContext> jwks = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
    verifier = new JwtVerifier(jwks, ISSUER, AUDIENCE);
  }

  /**
   * Mint a token, signing with {@code key} (use a foreign key to simulate
   * forgery).
   */
  private String mint(String issuer, String audience, Instant expiry,
                      List<String> roles, RSAKey key) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject("user-123")
        .issuer(issuer)
        .audience(audience)
        .claim("email", "user@lynx.test")
        .claim("roles", roles)
        .expirationTime(Date.from(expiry))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
        claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private String validToken() throws Exception {
    return mint(ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
        List.of("USER", "ADMIN"), signingKey);
  }

  @Test
  void verifiesValidTokenAndExtractsIdentity() throws Exception {
    UserContext ctx = verifier.verify(validToken());
    assertEquals("user-123", ctx.userId());
    assertEquals("user@lynx.test", ctx.email());
    assertTrue(ctx.hasRole("USER"));
    assertTrue(ctx.hasRole("ADMIN"));
  }

  @Test
  void rejectsExpiredToken() throws Exception {
    // Beyond Nimbus's default 60s clock-skew tolerance (which absorbs clock drift
    // between servers) — this token is unambiguously expired.
    String expired = mint(ISSUER, AUDIENCE, Instant.now().minusSeconds(120),
        List.of("USER"), signingKey);
    AuthException e = assertThrows(AuthException.class, () -> verifier.verify(expired));
    assertEquals(ErrorCode.UNAUTHORIZED, e.code());
  }

  @Test
  void rejectsWrongIssuer() throws Exception {
    String token = mint("https://evil.example", AUDIENCE, Instant.now().plusSeconds(300),
        List.of("USER"), signingKey);
    assertThrows(AuthException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsWrongAudience() throws Exception {
    String token = mint(ISSUER, "some-other-api", Instant.now().plusSeconds(300),
        List.of("USER"), signingKey);
    assertThrows(AuthException.class, () -> verifier.verify(token));
  }

  @Test
  void rejectsTokenSignedByForeignKey() throws Exception {
    // Attacker signs with their OWN key; our JWKS doesn't contain its public half.
    RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("k1").generate();
    String forged = mint(ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
        List.of("ADMIN"), attackerKey);
    assertThrows(AuthException.class, () -> verifier.verify(forged));
  }

  @Test
  void rejectsNullOrBlankToken() {
    assertThrows(AuthException.class, () -> verifier.verify(null));
    assertThrows(AuthException.class, () -> verifier.verify("  "));
  }

  @Test
  void rejectsGarbageToken() {
    assertThrows(AuthException.class, () -> verifier.verify("not.a.jwt"));
  }

  @Test
  void tokenWithoutRolesYieldsEmptyRoleSet() throws Exception {
    String token = mint(ISSUER, AUDIENCE, Instant.now().plusSeconds(300), null, signingKey);
    UserContext ctx = verifier.verify(token);
    assertTrue(ctx.roles().isEmpty());
  }
}
