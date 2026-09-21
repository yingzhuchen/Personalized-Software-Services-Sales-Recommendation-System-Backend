package com.example.jobrec.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Caps concurrent MySQL/origin loads while the Redis circuit is OPEN.
 * Permits are reentrant on the same thread so nested cache-aside loads
 * (recommendation keywords → corpus DF) do not deadlock.
 */
@Component
public class MysqlFallbackLimiter {
    private final RedisCircuitBreaker circuitBreaker;
    private final RedisCacheMetrics metrics;
    private final Semaphore semaphore;
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    public MysqlFallbackLimiter(
            RedisCircuitBreaker circuitBreaker,
            RedisCacheMetrics metrics,
            @Value("${app.cache.mysql-fallback.max-concurrent:32}") int maxConcurrent) {
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        this.semaphore = new Semaphore(Math.max(1, maxConcurrent), true);
    }

    public <T> T run(Supplier<T> loader) {
        if (circuitBreaker.getState() != RedisCircuitBreaker.State.OPEN) {
            return loader.get();
        }

        int currentDepth = depth.get();
        if (currentDepth > 0) {
            depth.set(currentDepth + 1);
            try {
                return loader.get();
            } finally {
                depth.set(currentDepth);
            }
        }

        if (!semaphore.tryAcquire()) {
            metrics.recordMysqlFallbackRejected();
            throw new MysqlFallbackRejectedException(
                    "MySQL fallback bulkhead full while Redis circuit is OPEN");
        }
        depth.set(1);
        try {
            metrics.recordMysqlFallback();
            return loader.get();
        } finally {
            depth.set(0);
            semaphore.release();
        }
    }
}
