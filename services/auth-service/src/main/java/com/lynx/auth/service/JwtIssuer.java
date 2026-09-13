package com.lynx.auth.service;

import com.lynx.auth.security.SigningKey;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Signs the two token shapes this service issues — see other-docs/11's
 * claims table. Both are RS256, both carry the same {@code iss}/{@code aud}
 * every {@code JwtVerifier} in the system already expects
 * ({@code https://auth.lynx} / {@code lynx-api}) — only {@code sub},
 * whether {@code email} is present, {@code roles}, and the lifetime differ.
 */
public class JwtIssuer {

  static final String ISSUER = "https://auth.lynx";
  static final String AUDIENCE = "lynx-api";

  /** 30 minutes — an end-user session token (other-docs/11 Decision 2: access-token-only, no refresh). */
  static final Duration USER_TOKEN_TTL = Duration.ofMinutes(30);

  /** 1 hour — matches {@code ServiceTokenProvider}'s 50-minute reuse window with a 10-minute buffer (other-docs/03 Decision 8). */
  static final Duration SERVICE_TOKEN_TTL = Duration.ofHours(1);

  private final SigningKey signingKey;
  private final Clock clock;

  public JwtIssuer(SigningKey signingKey) {
    this(signingKey, Clock.systemUTC());
  }

  JwtIssuer(SigningKey signingKey, Clock clock) {
    this.signingKey = signingKey;
    this.clock = clock;
  }

  /** A token for an authenticated end user — {@code sub} is their userId, carries their email and roles. */
  public IssuedToken issueUserToken(String userId, String email, List<String> roles) {
    Instant now = clock.instant();
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject(userId)
        .claim("email", email)
        .claim("roles", roles)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(USER_TOKEN_TTL)))
        .build();
    return sign(claims, USER_TOKEN_TTL);
  }

  /** A service-identity token (ADR-007 Option C) — {@code sub} is the client id, no email, roles from its registration. */
  public IssuedToken issueServiceToken(String clientId, List<String> roles) {
    Instant now = clock.instant();
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject(clientId)
        .claim("roles", roles)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(SERVICE_TOKEN_TTL)))
        .build();
    return sign(claims, SERVICE_TOKEN_TTL);
  }

  private IssuedToken sign(JWTClaimsSet claims, Duration ttl) {
    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(signingKey.signingKey().getKeyID())
        .build();
    SignedJWT signedJwt = new SignedJWT(header, claims);
    try {
      signedJwt.sign(new RSASSASigner(signingKey.signingKey()));
    } catch (JOSEException e) {
      // Signing a well-formed claims set with a freshly-generated key can't
      // meaningfully fail at runtime — a real failure here means something
      // is fundamentally wrong with the JVM's crypto provider, not a
      // recoverable per-request condition.
      throw new IllegalStateException("Failed to sign JWT", e);
    }
    return new IssuedToken(signedJwt.serialize(), ttl.getSeconds());
  }

  /** @param value the compact-serialized JWT; @param expiresInSeconds its lifetime from issuance */
  public record IssuedToken(String value, long expiresInSeconds) {
  }
}
