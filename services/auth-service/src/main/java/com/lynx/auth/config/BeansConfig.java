package com.lynx.auth.config;

import com.lynx.auth.repository.ServiceClientRepository;
import com.lynx.auth.repository.UserRepository;
import com.lynx.auth.security.SigningKey;
import com.lynx.auth.service.JwtIssuer;
import com.lynx.auth.service.ServiceTokenIssuerService;
import com.lynx.auth.service.UserAuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class BeansConfig {

  @Bean
  public SigningKey signingKey() {
    return new SigningKey();
  }

  @Bean
  public JwtIssuer jwtIssuer(SigningKey signingKey) {
    return new JwtIssuer(signingKey);
  }

  /** Same tool, same reasoning, for both user passwords and service-client secrets (other-docs/11 Decision 1/3). */
  @Bean
  public BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public UserAuthService userAuthService(
      UserRepository userRepository, BCryptPasswordEncoder passwordEncoder, JwtIssuer jwtIssuer) {
    return new UserAuthService(userRepository, passwordEncoder, jwtIssuer);
  }

  @Bean
  public ServiceTokenIssuerService serviceTokenIssuerService(
      ServiceClientRepository serviceClientRepository,
      BCryptPasswordEncoder passwordEncoder,
      JwtIssuer jwtIssuer) {
    return new ServiceTokenIssuerService(serviceClientRepository, passwordEncoder, jwtIssuer);
  }
}
