package com.lynx.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lynx.auth.security.SigningKey;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves the actual signed JWT's claims, not just that a string comes back. */
class JwtIssuerTest {

  private final JwtIssuer jwtIssuer = new JwtIssuer(new SigningKey());

  @Test
  void userTokenCarriesSubEmailRolesIssuerAudienceAndA30MinuteExpiry() throws Exception {
    JwtIssuer.IssuedToken token = jwtIssuer.issueUserToken("user-123", "a@b.com", List.of("user"));

    SignedJWT jwt = SignedJWT.parse(token.value());
    var claims = jwt.getJWTClaimsSet();
    assertThat(claims.getSubject()).isEqualTo("user-123");
    assertThat(claims.getStringClaim("email")).isEqualTo("a@b.com");
    assertThat(claims.getStringListClaim("roles")).containsExactly("user");
    assertThat(claims.getIssuer()).isEqualTo("https://auth.lynx");
    assertThat(claims.getAudience()).containsExactly("lynx-api");
    assertThat(token.expiresInSeconds()).isEqualTo(30 * 60);
  }

  @Test
  void serviceTokenCarriesTheClientIdAsSubjectNoEmailAndA1HourExpiry() throws Exception {
    JwtIssuer.IssuedToken token = jwtIssuer.issueServiceToken("saga-orchestrator", List.of("internal-service"));

    SignedJWT jwt = SignedJWT.parse(token.value());
    var claims = jwt.getJWTClaimsSet();
    assertThat(claims.getSubject()).isEqualTo("saga-orchestrator");
    assertThat(claims.getStringClaim("email")).isNull();
    assertThat(claims.getStringListClaim("roles")).containsExactly("internal-service");
    assertThat(token.expiresInSeconds()).isEqualTo(60 * 60);
  }

  @Test
  void theSignatureActuallyVerifiesAgainstTheSigningKeysOwnPublicHalf() throws Exception {
    SigningKey signingKey = new SigningKey();
    JwtIssuer issuer = new JwtIssuer(signingKey);
    SignedJWT jwt = SignedJWT.parse(issuer.issueUserToken("user-123", "a@b.com", List.of("user")).value());

    boolean verified = jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(signingKey.publicJwk()));

    assertThat(verified).isTrue();
  }
}
