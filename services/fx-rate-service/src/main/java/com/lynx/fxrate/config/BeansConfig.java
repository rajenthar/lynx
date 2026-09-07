package com.lynx.fxrate.config;

import com.lynx.common.web.GlobalExceptionHandler;
import com.lynx.idempotency.IdempotencyCache;
import com.lynx.idempotency.IdempotencyGuard;
import com.lynx.idempotency.InMemoryIdempotencyCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * {@link IdempotencyCache}: in-memory, unlike ledger-service's Redis-backed
 * choice — deliberately, not an oversight. This service isn't horizontally
 * scaled yet and has no real caller yet either, so a cache wiped on
 * restart costs nothing in practice today; the DB's own
 * {@code UNIQUE(execution_id)} constraint is the real correctness
 * guarantee regardless (ADR-004's "cache for speed, database for
 * correctness" principle, unchanged here). Revisit if/when this runs
 * multiple instances or needs to survive a deploy without a fast-path
 * cache miss — same reasoning ledger-service's own BeansConfig documents
 * for its opposite choice.
 */
@Configuration
@Import(GlobalExceptionHandler.class)
public class BeansConfig {

  @Bean
  public IdempotencyCache idempotencyCache() {
    return new InMemoryIdempotencyCache();
  }

  @Bean
  public IdempotencyGuard idempotencyGuard(IdempotencyCache idempotencyCache) {
    return new IdempotencyGuard(idempotencyCache);
  }
}
