package com.lynx.auth.api;

import com.lynx.auth.dto.AuthDtos.TokenResponse;
import com.lynx.auth.service.JwtIssuer;
import com.lynx.auth.service.ServiceTokenIssuerService;
import com.lynx.common.error.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OAuth2 Client Credentials grant's server side (RFC 6749 §4.4) —
 * {@code ServiceTokenProvider.fetchToken}'s exact counterpart: {@code
 * Authorization: Basic base64(clientId:clientSecret)} ({@code
 * client_secret_basic}, RFC 6749 §2.3.1) plus a form-encoded {@code
 * grant_type=client_credentials} body. Public in the sense that no bearer
 * token is required to call it — the Basic credentials themselves ARE the
 * authentication.
 */
@RestController
@RequestMapping("/auth")
public class TokenController {

  private final ServiceTokenIssuerService serviceTokenIssuerService;

  public TokenController(ServiceTokenIssuerService serviceTokenIssuerService) {
    this.serviceTokenIssuerService = serviceTokenIssuerService;
  }

  @PostMapping("/token")
  public TokenResponse token(
      @RequestParam("grant_type") String grantType, HttpServletRequest request) {
    String[] credentials = decodeBasicAuth(request.getHeader("Authorization"));
    JwtIssuer.IssuedToken token =
        serviceTokenIssuerService.issueToken(grantType, credentials[0], credentials[1]);
    return TokenResponse.bearer(token.value(), token.expiresInSeconds());
  }

  private static String[] decodeBasicAuth(String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
      throw AuthException.unauthorized("Missing Basic authorization header");
    }
    String encoded = authorizationHeader.substring("Basic ".length());
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw AuthException.unauthorized("Malformed Basic authorization header");
    }
    int colon = decoded.indexOf(':');
    if (colon < 0) {
      throw AuthException.unauthorized("Malformed Basic authorization header");
    }
    return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
  }
}
