package com.example.jobrec.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Process-local cache in front of Redis. Fresh entries are served within {@code ttlMs}.
 * When Redis is down, slightly stale entries are still served until {@code ttlMs + staleMs}
 * so a Redis outage does not immediately dump traffic onto MySQL.
 */
@Component
public class LocalL1Cache {
    private final Cache<String, Entry> cache;
    private final long ttlMs;
    private final long staleMs;
    private final Clock clock;

    @Autowired
    public LocalL1Cache(
            @Value("${app.cache.l1.max-size:10000}") int maxSize,
            @Value("${app.cache.l1.ttl-ms:60000}") long ttlMs,
            @Value("${app.cache.l1.stale-ms:600000}") long staleMs) {
        this(maxSize, ttlMs, staleMs, Clock.systemUTC());
    }

    public LocalL1Cache(int maxSize, long ttlMs, long staleMs, Clock clock) {
        this.ttlMs = Math.max(1L, ttlMs);
        this.staleMs = Math.max(0L, staleMs);
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, maxSize))
                .expireAfterWrite(this.ttlMs + this.staleMs, TimeUnit.MILLISECONDS)
                .build();
    }

    public String get(String key, boolean allowStale) {
        Entry entry = cache.getIfPresent(key);
        if (entry == null) {
            return null;
        }
        long now = clock.millis();
        if (now < entry.freshUntilMs) {
            return entry.value;
        }
        if (allowStale && now < entry.staleUntilMs) {
            return entry.value;
        }
        return null;
    }

    public void put(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        long now = clock.millis();
        cache.put(key, new Entry(value, now + ttlMs, now + ttlMs + staleMs));
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public void invalidatePrefix(String prefix) {
        cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    void invalidateAll() {
        cache.invalidateAll();
    }

    static final class Entry {
        final String value;
        final long freshUntilMs;
        final long staleUntilMs;

        Entry(String value, long freshUntilMs, long staleUntilMs) {
            this.value = value;
            this.freshUntilMs = freshUntilMs;
            this.staleUntilMs = staleUntilMs;
        }
    }
}
