package com.lynx.security;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.lynx.common.error.AuthException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Verifies RS256-signed JWTs and extracts the caller identity.
 *
 * <p>RS256 is asymmetric: auth-service signs tokens with a PRIVATE key; every other
 * service verifies with the matching PUBLIC key, fetched from auth-service's JWKS
 * endpoint. No shared secret is distributed — a verifying service can never mint
 * tokens, only check them. (Contrast HS256, where the same secret signs and verifies,
 * so every service holding it could forge tokens.)
 *
 * <p>The public keys are cached (see {@link #fromJwksUrl}) so verification is a local
 * signature check on the hot path — the JWKS endpoint is hit only on cache miss or
 * key rotation, not per request.
 *
 * <p>A token is accepted only if ALL hold: signature valid, issuer matches, audience
 * matches, not expired, and a {@code sub} claim is present. Any failure → 401.
 */
public class JwtVerifier {

  static final String CLAIM_EMAIL = "email";
  static final String CLAIM_ROLES = "roles";

  private final ConfigurableJWTProcessor<SecurityContext> processor;

  /**
   * @param jwkSource source of verification keys (a cached remote JWKS in production,
   *                  an in-memory set in tests)
   * @param issuer    the exact {@code iss} the token must carry (auth-service's id)
   * @param audience  the {@code aud} this service expects, or null to skip the check
   */
  public JwtVerifier(JWKSource<SecurityContext> jwkSource, String issuer, String audience) {
    this.processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));

    JWTClaimsSet exactMatch = new JWTClaimsSet.Builder().issuer(issuer).build();
    Set<String> requiredClaims = Set.of("sub", "exp");
    // DefaultJWTClaimsVerifier always checks exp/nbf; we add issuer + audience + sub.
    processor.setJWTClaimsSetVerifier(
        audience == null
            ? new DefaultJWTClaimsVerifier<>(exactMatch, requiredClaims)
            : new DefaultJWTClaimsVerifier<>(audience, exactMatch, requiredClaims));
  }

  /**
   * Build a verifier backed by a remote JWKS endpoint with default caching
   * (in-memory, time-based refresh + rate limiting on refetch).
   */
  public static JwtVerifier fromJwksUrl(String jwksUrl, String issuer, String audience) {
    try {
      JWKSource<SecurityContext> source =
          JWKSourceBuilder.create(new URL(jwksUrl)).build();
      return new JwtVerifier(source, issuer, audience);
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("Invalid JWKS URL: " + jwksUrl, e);
    }
  }

  /**
   * Verify a compact JWT (no "Bearer " prefix) and return the caller identity.
   *
   * @throws AuthException 401 if the token is missing, malformed, expired, or fails
   *     any signature/issuer/audience check
   */
  public UserContext verify(String token) {
    if (token == null || token.isBlank()) {
      throw AuthException.unauthorized("Missing bearer token");
    }
    JWTClaimsSet claims;
    try {
      claims = processor.process(token, null);
    } catch (Exception e) {
      // Deliberately generic message: never leak WHY (expired vs bad signature vs
      // wrong issuer) to the caller — that only aids an attacker probing tokens.
      throw AuthException.unauthorized("Invalid or expired token");
    }
    return toUserContext(claims);
  }

  private UserContext toUserContext(JWTClaimsSet claims) {
    try {
      String userId = claims.getSubject();
      String email = claims.getStringClaim(CLAIM_EMAIL);
      List<String> roleList = claims.getStringListClaim(CLAIM_ROLES);
      Set<String> roles = roleList == null ? Set.of() : new HashSet<>(roleList);
      return new UserContext(userId, email, roles);
    } catch (java.text.ParseException e) {
      throw AuthException.unauthorized("Malformed token claims");
    }
  }
}
