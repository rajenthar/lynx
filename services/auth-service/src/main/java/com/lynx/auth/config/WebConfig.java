package com.lynx.auth.config;

import com.lynx.common.web.GlobalExceptionHandler;
import com.lynx.security.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Only the correlation-id filter — unlike every other Lynx service, there is
 * no {@code JwtAuthFilter} here. Every endpoint this service exposes
 * (register/login/token/jwks) is either public by design or authenticates
 * its caller with its own explicit logic ({@code TokenController}'s Basic
 * auth decode), never a bearer JWT this service itself issues. Activates the
 * shared {@link GlobalExceptionHandler} explicitly, per other-docs/01's
 * deferred-introduction design.
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
}
