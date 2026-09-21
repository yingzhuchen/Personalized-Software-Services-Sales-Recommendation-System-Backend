package com.example.jobrec.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlFallbackLimiterTest {
    @Test
    void doesNotLimitWhenCircuitClosed() {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(5, 30_000L, 1L);
        RedisCacheMetrics metrics = new RedisCacheMetrics(circuitBreaker);
        MysqlFallbackLimiter limiter = new MysqlFallbackLimiter(circuitBreaker, metrics, 1);

        assertEquals("ok", limiter.run(() -> "ok"));
        assertEquals(0L, metrics.getMysqlFallbacks());
        assertEquals(0L, metrics.getMysqlFallbackRejected());
    }

    @Test
    void nestedLoadsOnSameThreadDoNotConsumeExtraPermits() {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(1, 30_000L, 1L);
        circuitBreaker.recordFailure();
        RedisCacheMetrics metrics = new RedisCacheMetrics(circuitBreaker);
        MysqlFallbackLimiter limiter = new MysqlFallbackLimiter(circuitBreaker, metrics, 1);

        String result = limiter.run(() -> limiter.run(() -> "nested"));
        assertEquals("nested", result);
        assertEquals(1L, metrics.getMysqlFallbacks());
    }

    @Test
    void rejectsWhenOpenAndPermitsExhausted() throws Exception {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(1, 30_000L, 1L);
        circuitBreaker.recordFailure();
        RedisCacheMetrics metrics = new RedisCacheMetrics(circuitBreaker);
        MysqlFallbackLimiter limiter = new MysqlFallbackLimiter(circuitBreaker, metrics, 1);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> limiter.run(() -> {
            held.countDown();
            await(release);
            return "held";
        }));
        holder.start();
        assertTrue(held.await(2, TimeUnit.SECONDS));

        assertThrows(MysqlFallbackRejectedException.class, () -> limiter.run(() -> "nope"));
        assertEquals(1L, metrics.getMysqlFallbackRejected());

        release.countDown();
        holder.join(2_000L);
        assertFalse(holder.isAlive());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
