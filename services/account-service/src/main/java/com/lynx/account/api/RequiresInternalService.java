package com.lynx.account.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as callable only by a proven internal-service
 * caller — a token carrying the {@code internal-service} role, already
 * verified by {@link com.lynx.account.config.JwtAuthFilter} before this
 * method is ever invoked. Enforced by {@link RequiresInternalServiceAspect}.
 *
 * <p>A declarative alternative to a manual {@code if
 * (!userContext.hasRole(...))} check — deliberately NOT Spring Security's
 * {@code @PreAuthorize}: no service in this project uses Spring Security,
 * and adopting it here alone would mean a real new framework dependency
 * plus bridging {@code JwtVerifier}'s output into {@code
 * SecurityContextHolder}, just to replace a two-line check. This
 * annotation, backed by a plain Spring AOP aspect, gets the same
 * one-line-on-the-method ergonomics without either.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresInternalService {
}
