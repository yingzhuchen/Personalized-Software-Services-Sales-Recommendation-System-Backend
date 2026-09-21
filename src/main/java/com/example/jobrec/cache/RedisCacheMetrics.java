package com.example.jobrec.cache;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process Redis cache hit/miss/error counters for monitoring and alerting.
 */
@Component
public class RedisCacheMetrics {
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong circuitOpenSkips = new AtomicLong();
    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l1Misses = new AtomicLong();
    private final AtomicLong l1StaleHits = new AtomicLong();
    private final AtomicLong coalescedJoins = new AtomicLong();
    private final AtomicLong mysqlFallbacks = new AtomicLong();
    private final AtomicLong mysqlFallbackRejected = new AtomicLong();
    private final RedisCircuitBreaker circuitBreaker;

    public RedisCacheMetrics(RedisCircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public void recordHit() {
        hits.incrementAndGet();
    }

    public void recordMiss() {
        misses.incrementAndGet();
    }

    public void recordError() {
        errors.incrementAndGet();
    }

    public void recordCircuitOpenSkip() {
        circuitOpenSkips.incrementAndGet();
    }

    public void recordL1Hit() {
        l1Hits.incrementAndGet();
    }

    public void recordL1Miss() {
        l1Misses.incrementAndGet();
    }

    public void recordL1StaleHit() {
        l1StaleHits.incrementAndGet();
        l1Hits.incrementAndGet();
    }

    public void recordCoalescedJoin() {
        coalescedJoins.incrementAndGet();
    }

    public void recordMysqlFallback() {
        mysqlFallbacks.incrementAndGet();
    }

    public void recordMysqlFallbackRejected() {
        mysqlFallbackRejected.incrementAndGet();
    }

    public long getHits() {
        return hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public long getErrors() {
        return errors.get();
    }

    public long getCircuitOpenSkips() {
        return circuitOpenSkips.get();
    }

    public long getL1Hits() {
        return l1Hits.get();
    }

    public long getL1Misses() {
        return l1Misses.get();
    }

    public long getL1StaleHits() {
        return l1StaleHits.get();
    }

    public long getCoalescedJoins() {
        return coalescedJoins.get();
    }

    public long getMysqlFallbacks() {
        return mysqlFallbacks.get();
    }

    public long getMysqlFallbackRejected() {
        return mysqlFallbackRejected.get();
    }

    public double getHitRate() {
        long total = hits.get() + misses.get();
        if (total == 0L) {
            return 0.0;
        }
        return (double) hits.get() / (double) total;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("hits", getHits());
        snapshot.put("misses", getMisses());
        snapshot.put("errors", getErrors());
        snapshot.put("circuitOpenSkips", getCircuitOpenSkips());
        snapshot.put("l1Hits", getL1Hits());
        snapshot.put("l1Misses", getL1Misses());
        snapshot.put("l1StaleHits", getL1StaleHits());
        snapshot.put("coalescedJoins", getCoalescedJoins());
        snapshot.put("mysqlFallbacks", getMysqlFallbacks());
        snapshot.put("mysqlFallbackRejected", getMysqlFallbackRejected());
        snapshot.put("hitRate", getHitRate());
        snapshot.put("circuitState", circuitBreaker.getState().name());
        snapshot.put("redisAvailable", circuitBreaker.isAvailable());
        snapshot.put("consecutiveFailures", circuitBreaker.getConsecutiveFailures());
        snapshot.put("failureThreshold", circuitBreaker.getFailureThreshold());
        snapshot.put("openDurationMs", circuitBreaker.getOpenDurationMs());
        return snapshot;
    }

    /** Test helper. */
    public void reset() {
        hits.set(0);
        misses.set(0);
        errors.set(0);
        circuitOpenSkips.set(0);
        l1Hits.set(0);
        l1Misses.set(0);
        l1StaleHits.set(0);
        coalescedJoins.set(0);
        mysqlFallbacks.set(0);
        mysqlFallbackRejected.set(0);
    }
}
