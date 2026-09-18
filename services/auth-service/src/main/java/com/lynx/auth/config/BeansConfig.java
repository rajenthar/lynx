package com.lynx.auth.config;

import com.lynx.auth.client.AccountServiceClient;
import com.lynx.auth.repository.EmailOtpRepository;
import com.lynx.auth.repository.ServiceClientRepository;
import com.lynx.auth.repository.UserRepository;
import com.lynx.auth.security.SigningKey;
import com.lynx.auth.service.JwtIssuer;
import com.lynx.auth.service.MailService;
import com.lynx.auth.service.OtpService;
import com.lynx.auth.service.ServiceTokenIssuerService;
import com.lynx.auth.service.SmtpMailService;
import com.lynx.auth.service.UserAuthService;
import com.lynx.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestClient;

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

  /** Same tool, same reasoning, for both user passwords and service-client secrets. */
  @Bean
  public BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public MailService mailService(
      JavaMailSender mailSender, @Value("${lynx.mail.from-address}") String fromAddress) {
    return new SmtpMailService(mailSender, fromAddress);
  }

  @Bean
  public OtpService otpService(
      EmailOtpRepository otpRepository, UserRepository userRepository, MailService mailService) {
    return new OtpService(otpRepository, userRepository, mailService);
  }

  @Bean
  public UserAuthService userAuthService(
      UserRepository userRepository, BCryptPasswordEncoder passwordEncoder,
      JwtIssuer jwtIssuer, OtpService otpService) {
    return new UserAuthService(userRepository, passwordEncoder, jwtIssuer, otpService);
  }

  /**
   * This service verifying its OWN tokens, only for {@code /auth/me} —
   * fetches its own JWKS over loopback HTTP, the exact same mechanism every
   * OTHER service already uses to verify tokens auth-service issued.
   */
  @Bean
  public JwtVerifier jwtVerifier(
      @Value("${lynx.security.jwks-url}") String jwksUrl,
      @Value("${lynx.security.issuer}") String issuer,
      @Value("${lynx.security.audience}") String audience) {
    return JwtVerifier.fromJwksUrl(jwksUrl, issuer, audience);
  }

  @Bean
  public AccountServiceClient accountServiceClient(
      RestClient.Builder restClientBuilder,
      @Value("${lynx.clients.account-service.base-url}") String baseUrl, JwtIssuer jwtIssuer) {
    // The INJECTED builder, not RestClient.builder() called directly — see
    // account-service's own BeansConfig for why this matters for trace
    // propagation (docs/html/metrics_traces_logs/trace-propagation.html).
    return new AccountServiceClient(restClientBuilder.baseUrl(baseUrl).build(), jwtIssuer);
  }

  @Bean
  public ServiceTokenIssuerService serviceTokenIssuerService(
      ServiceClientRepository serviceClientRepository,
      BCryptPasswordEncoder passwordEncoder,
      JwtIssuer jwtIssuer) {
    return new ServiceTokenIssuerService(serviceClientRepository, passwordEncoder, jwtIssuer);
  }
}
