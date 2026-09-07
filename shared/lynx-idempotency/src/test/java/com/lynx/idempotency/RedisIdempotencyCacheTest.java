package com.lynx.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPool;

/** Real Redis via Testcontainers — proves the wire format round-trips, not simulated. */
@Testcontainers
class RedisIdempotencyCacheTest {

  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static JedisPool pool;
  static RedisIdempotencyCache cache;

  record SampleResponse(String sagaId, int amount) {
  }

  @BeforeAll
  static void startRedis() {
    redis.start();
    pool = new JedisPool(redis.getHost(), redis.getMappedPort(6379));
    cache = new RedisIdempotencyCache(pool);
  }

  @AfterAll
  static void stopRedis() {
    pool.close();
    redis.stop();
  }

  @Test
  void putThenGetRoundTripsTheSameValue() {
    SampleResponse response = new SampleResponse("saga-1", 100);
    cache.put("key-1", response, Duration.ofMinutes(5));

    Optional<SampleResponse> retrieved = cache.get("key-1", SampleResponse.class);

    assertTrue(retrieved.isPresent());
    assertEquals(response, retrieved.get());
  }

  @Test
  void getOnMissingKeyReturnsEmpty() {
    Optional<SampleResponse> retrieved = cache.get("no-such-key", SampleResponse.class);

    assertTrue(retrieved.isEmpty());
  }

  @Test
  void expiredEntryIsNotReturned() {
    cache.put("key-expiring", new SampleResponse("saga-2", 50), Duration.ofSeconds(1));

    // Setex with a 1s TTL; a 0-second TTL isn't supported by Redis, so this
    // simulates the same "gone after ttl" behavior InMemoryIdempotencyCache's
    // own expiry test uses a Clock for — here we just wait past a very short TTL.
    try {
      Thread.sleep(1500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertTrue(cache.get("key-expiring", SampleResponse.class).isEmpty());
  }
}
