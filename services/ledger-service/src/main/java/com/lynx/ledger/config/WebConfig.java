package com.lynx.ledger.config;

import com.lynx.common.web.GlobalExceptionHandler;
import com.lynx.security.CorrelationIdFilter;
import com.lynx.security.JwtVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Registers the filter chain in order: correlation id first (so even a
 * rejected/unauthenticated request is traceable), then JWT verification.
 * Activates the shared {@link GlobalExceptionHandler} explicitly, per
 * other-docs/01's deferred-introduction design.
 */
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
