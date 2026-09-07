package com.lynx.ledger.config;

import com.lynx.idempotency.IdempotencyCache;
import com.lynx.idempotency.IdempotencyGuard;
import com.lynx.idempotency.RedisIdempotencyCache;
import com.lynx.security.JwtVerifier;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import redis.clients.jedis.JedisPool;

/**
 * Wiring for cross-cutting shared-library beans.
 *
 * <p>{@link IdempotencyCache}: Redis-backed, even though ledger-service runs
 * as a single instance today. Correctness never depends on this cache
 * either way (ADR-004 — the database UNIQUE constraint is the real judge),
 * but {@code InMemoryIdempotencyCache} is wiped by every process restart,
 * turning every in-flight client retry window into a guaranteed cache miss
 * right after a deploy. Redis survives restarts, so the fast path keeps
 * working across deploys too, not only across horizontally-scaled
 * instances — see {@link RedisIdempotencyCache}'s own javadoc.
 */
@Configuration
public class BeansConfig {

  @Bean
  public JwtVerifier jwtVerifier(
      @Value("${lynx.security.jwks-url}") String jwksUrl,
      @Value("${lynx.security.issuer}") String issuer,
      @Value("${lynx.security.audience}") String audience) {
    return JwtVerifier.fromJwksUrl(jwksUrl, issuer, audience);
  }

  @Bean(destroyMethod = "close")
  public JedisPool jedisPool(
      @Value("${lynx.redis.host}") String host,
      @Value("${lynx.redis.port}") int port) {
    return new JedisPool(host, port);
  }

  @Bean
  public IdempotencyCache idempotencyCache(JedisPool jedisPool) {
    return new RedisIdempotencyCache(jedisPool);
  }

  @Bean
  public IdempotencyGuard idempotencyGuard(IdempotencyCache idempotencyCache) {
    return new IdempotencyGuard(idempotencyCache);
  }

  @Bean
  public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
    return new JpaTransactionManager(emf);
  }

  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }
}
