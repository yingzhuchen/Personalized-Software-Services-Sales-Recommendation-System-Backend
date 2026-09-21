package com.example.jobrec.cache;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class LocalL1CacheTest {
    @Test
    void servesFreshEntriesAndHidesExpiredWhenStaleNotAllowed() {
        AtomicLong millis = new AtomicLong(1_000_000L);
        LocalL1Cache cache = new LocalL1Cache(10, 100L, 1_000L, clock(millis));

        cache.put("search:crm", "[1]");
        assertEquals("[1]", cache.get("search:crm", false));

        millis.addAndGet(150L);
        assertNull(cache.get("search:crm", false));
        assertEquals("[1]", cache.get("search:crm", true));
    }

    @Test
    void dropsEntriesAfterStaleWindow() {
        AtomicLong millis = new AtomicLong(1_000_000L);
        LocalL1Cache cache = new LocalL1Cache(10, 100L, 200L, clock(millis));

        cache.put("k", "v");
        millis.addAndGet(400L);
        assertNull(cache.get("k", true));
    }

    @Test
    void invalidateRemovesKey() {
        LocalL1Cache cache = new LocalL1Cache(10, 1_000L, 1_000L, Clock.systemUTC());
        cache.put("history:userId=u", "[]");
        cache.invalidate("history:userId=u");
        assertNull(cache.get("history:userId=u", true));
    }

    @Test
    void invalidatePrefixRemovesMatchingKeys() {
        LocalL1Cache cache = new LocalL1Cache(10, 1_000L, 1_000L, Clock.systemUTC());
        cache.put("corpus:total_items", "9");
        cache.put("corpus:keyword_df:crm", "3");
        cache.put("search:crm", "[]");

        cache.invalidatePrefix("corpus:");

        assertNull(cache.get("corpus:total_items", true));
        assertNull(cache.get("corpus:keyword_df:crm", true));
        assertEquals("[]", cache.get("search:crm", true));
    }

    public static Clock clock(AtomicLong millis) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        };
    }
}
