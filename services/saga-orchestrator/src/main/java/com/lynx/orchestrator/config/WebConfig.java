package com.lynx.orchestrator.config;

import com.lynx.common.web.GlobalExceptionHandler;
import com.lynx.security.CorrelationIdFilter;
import com.lynx.security.JwtVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Same filter ordering as ledger-service's own {@code WebConfig}. */
@Configuration
@Import(GlobalExceptionHandler.class)
public class WebConfig {

  @Bean
  public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    FilterRegistrationBean<CorrelationIdFilter> registration =
        new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(1);
    return registration;
  }

  @Bean
  public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtVerifier jwtVerifier) {
    FilterRegistrationBean<JwtAuthFilter> registration =
        new FilterRegistrationBean<>(new JwtAuthFilter(jwtVerifier));
    registration.setOrder(2);
    return registration;
  }
}
