package com.lynx.auth.service;

import com.lynx.auth.domain.ServiceClient;
import com.lynx.auth.repository.ServiceClientRepository;
import com.lynx.common.error.AuthException;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The server side of the OAuth2 Client Credentials grant (RFC 6749 §4.4)
 * {@code ServiceTokenProvider} already speaks (other-docs/03 Decision 8;
 * other-docs/11 Decision 3). Looks up the caller by {@code client_id},
 * checks its BCrypt-hashed secret, and mints a token carrying that client's
 * registered roles (e.g. {@code internal-service}).
 */
public class ServiceTokenIssuerService {

  private static final String SUPPORTED_GRANT_TYPE = "client_credentials";

  private final ServiceClientRepository serviceClientRepository;
  private final BCryptPasswordEncoder passwordEncoder;
  private final JwtIssuer jwtIssuer;

  public ServiceTokenIssuerService(
      ServiceClientRepository serviceClientRepository,
      BCryptPasswordEncoder passwordEncoder,
      JwtIssuer jwtIssuer) {
    this.serviceClientRepository = serviceClientRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtIssuer = jwtIssuer;
  }

  public JwtIssuer.IssuedToken issueToken(String grantType, String clientId, String clientSecret) {
    if (!SUPPORTED_GRANT_TYPE.equals(grantType)) {
      throw AuthException.unauthorized("Unsupported grant_type: " + grantType);
    }
    if (clientId == null || clientSecret == null) {
      throw AuthException.unauthorized("Missing client credentials");
    }
    ServiceClient client = serviceClientRepository.findByClientId(clientId)
        // Same message whether the client id is unknown or the secret is
        // wrong — never reveal which one failed to a caller probing credentials.
        .orElseThrow(() -> AuthException.unauthorized("Invalid client credentials"));
    if (!passwordEncoder.matches(clientSecret, client.getClientSecretHash())) {
      throw AuthException.unauthorized("Invalid client credentials");
    }
    return jwtIssuer.issueServiceToken(client.getClientId(), Arrays.asList(client.getRoles()));
  }
}
