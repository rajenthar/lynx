package com.lynx.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed {@link IdempotencyCache}, shared across all instances of a
 * service.
 *
 * <p>Used even for a single-instance deployment (not just for horizontal
 * scaling): a process restart wipes {@link InMemoryIdempotencyCache}
 * entirely, turning every in-flight retry window into a guaranteed cache
 * miss (falling through to the database — still correct per ADR-004, just
 * slower). Redis survives the service process restarting, so the fast path
 * keeps working across deploys/restarts too, not only across instances.
 *
 * <p>Correctness never depends on this class: per {@link IdempotencyCache}'s
 * own contract, the database's UNIQUE constraint is the real judge — wiping
 * this cache (or Redis itself going down) only costs latency, never
 * correctness. On a Redis error, {@link #get} returns empty rather than
 * throwing, so a cache outage degrades to "every request hits the
 * database" instead of failing the request outright.
 */
public final class RedisIdempotencyCache implements IdempotencyCache {

  private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCache.class);

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private final JedisPool pool;

  public RedisIdempotencyCache(JedisPool pool) {
    this.pool = pool;
  }

  @Override
  public <T> Optional<T> get(String cacheKey, Class<T> type) {
    try (var jedis = pool.getResource()) {
      String json = jedis.get(cacheKey);
      if (json == null) {
        return Optional.empty();
      }
      return Optional.of(MAPPER.readValue(json, type));
    } catch (Exception e) {
      // Cache is speed-only (ADR-004): a Redis/deserialization problem
      // degrades to "treat as a miss," never fails the request — but this
      // is worth a WARN, since a persistently degraded cache means every
      // retry is silently paying the slower DB-recovery path.
      log.warn("Idempotency cache read failed for key [{}] — degrading to cache miss", cacheKey, e);
      return Optional.empty();
    }
  }

  @Override
  public void put(String cacheKey, Object response, Duration ttl) {
    try (var jedis = pool.getResource()) {
      String json = MAPPER.writeValueAsString(response);
      jedis.setex(cacheKey, ttl.toSeconds(), json);
    } catch (Exception e) {
      // Best-effort: failing to cache a successful response never fails
      // the request itself — the next retry just falls through to the DB.
      log.warn("Idempotency cache write failed for key [{}] — next retry will fall through to the DB", cacheKey, e);
    }
  }
}
