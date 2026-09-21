package com.example.jobrec.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCacheMetricsTest {
    private RedisCacheMetrics metrics;

    @BeforeEach
    void setUp() {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(5, 30_000L, 60_000L);
        metrics = new RedisCacheMetrics(circuitBreaker);
        metrics.reset();
    }

    @Test
    void hitRateIsZeroWhenNoSamples() {
        assertEquals(0.0, metrics.getHitRate());
    }

    @Test
    void hitRateReflectsHitsAndMisses() {
        metrics.recordHit();
        metrics.recordHit();
        metrics.recordMiss();

        assertEquals(2L, metrics.getHits());
        assertEquals(1L, metrics.getMisses());
        assertEquals(2.0 / 3.0, metrics.getHitRate(), 1e-9);
    }

    @Test
    void snapshotIncludesCircuitAndCounters() {
        metrics.recordHit();
        metrics.recordError();
        metrics.recordCircuitOpenSkip();

        Map<String, Object> snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.get("hits"));
        assertEquals(0L, snapshot.get("misses"));
        assertEquals(1L, snapshot.get("errors"));
        assertEquals(1L, snapshot.get("circuitOpenSkips"));
        assertEquals(0L, snapshot.get("l1Hits"));
        assertEquals(0L, snapshot.get("mysqlFallbackRejected"));
        assertEquals("CLOSED", snapshot.get("circuitState"));
        assertTrue((Boolean) snapshot.get("redisAvailable"));
    }
}
