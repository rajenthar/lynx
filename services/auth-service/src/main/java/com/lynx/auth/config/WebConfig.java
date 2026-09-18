package com.lynx.auth.config;

import com.lynx.common.web.GlobalExceptionHandler;
import com.lynx.security.CorrelationIdFilter;
import com.lynx.security.JwtVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The correlation-id filter (every path) plus {@code JwtAuthFilter}, scoped
 * to ONLY {@code /auth/me} (both {@code GET}, the profile read, and
 * {@code PATCH}, changing name/password — a servlet URL-pattern mapping is
 * per-path, not per-HTTP-method, so one pattern covers both) — every other
 * endpoint this service exposes (register/login/verify-otp/resend-otp/
 * token/jwks) is either public by design or authenticates its caller with
 * its own explicit logic ({@code TokenController}'s Basic auth decode). Activates the shared
 * {@link GlobalExceptionHandler} explicitly — a deliberate, deferred
 * introduction rather than auto-scanning.
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
    registration.addUrlPatterns("/auth/me");
    registration.setOrder(2);
    return registration;
  }
}
