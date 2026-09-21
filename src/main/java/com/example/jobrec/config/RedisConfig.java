package com.example.jobrec.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis is configured for LRU eviction at the server level:
 *   maxmemory 256mb
 *   maxmemory-policy allkeys-lru
 *
 * Application code does not set TTL; when memory is full, Redis evicts
 * least-recently-used keys automatically.
 *
 * Stampede protection when Redis is down (see application.properties):
 *   Local L1 cache with stale-if-error
 *   Request coalescing (singleflight) for identical origin loads
 *   Per-JVM MySQL fallback bulkhead while the circuit is OPEN
 *
 * Client resilience (see application.properties):
 *   spring.redis.timeout / connect-timeout — command & connect timeouts
 *   app.redis.circuit-breaker.* — fail-open circuit breaker thresholds
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
