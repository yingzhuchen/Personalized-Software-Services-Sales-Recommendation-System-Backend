package com.example.jobrec.service;

import com.example.jobrec.cache.LocalL1Cache;
import com.example.jobrec.cache.LocalL1CacheTest;
import com.example.jobrec.cache.MysqlFallbackLimiter;
import com.example.jobrec.cache.RedisCacheMetrics;
import com.example.jobrec.cache.RedisCircuitBreaker;
import com.example.jobrec.cache.RequestCoalescer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisCacheServiceTest {
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private RedisCircuitBreaker circuitBreaker;
    private RedisCacheMetrics metrics;
    private RedisCacheService cacheService;

    @BeforeEach
    void setUp() {
        circuitBreaker = new RedisCircuitBreaker(2, 30_000L, 1L);
        metrics = new RedisCacheMetrics(circuitBreaker);
        cacheService = new RedisCacheService(redis, circuitBreaker, metrics);
    }

    @Test
    void getSearchResult_recordsHitOnCachedValue() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("search:lat=1.0&lon=2.0&keyword=crm")).thenReturn("[{\"id\":\"1\"}]");

        String result = cacheService.getSearchResult(1.0, 2.0, "crm");

        assertEquals("[{\"id\":\"1\"}]", result);
        assertEquals(1L, metrics.getHits());
        assertEquals(0L, metrics.getMisses());
        assertEquals(RedisCircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void getSearchResult_recordsMissOnNull() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        assertNull(cacheService.getSearchResult(1.0, 2.0, "crm"));
        assertEquals(0L, metrics.getHits());
        assertEquals(1L, metrics.getMisses());
    }

    @Test
    void getSearchResult_failsOpenToNullOnRedisException() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("timeout"));

        assertNull(cacheService.getSearchResult(1.0, 2.0, "crm"));
        assertEquals(1L, metrics.getErrors());
        assertEquals(1L, metrics.getMisses());
        assertEquals(1, circuitBreaker.getConsecutiveFailures());
    }

    @Test
    void getFavoriteResult_failsOpenAndTripsCircuitAfterThreshold() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("connection reset"));

        assertNull(cacheService.getFavoriteResult("user-1"));
        assertNull(cacheService.getFavoriteResult("user-1"));

        assertEquals(RedisCircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(2L, metrics.getErrors());
        assertEquals(2L, metrics.getMisses());

        assertNull(cacheService.getFavoriteResult("user-1"));
        assertEquals(1L, metrics.getCircuitOpenSkips());
        verify(valueOps, times(2)).get(anyString());
    }

    @Test
    void setFavoriteResult_swallowsWriteFailures() {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("write timeout")).when(valueOps).set(anyString(), anyString());

        cacheService.setFavoriteResult("user-1", "[]");

        assertEquals(1L, metrics.getErrors());
        assertEquals(1, circuitBreaker.getConsecutiveFailures());
    }

    @Test
    void setFavoriteResult_skipsWhenCircuitOpen() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("down"));
        cacheService.getFavoriteResult("u");
        cacheService.getFavoriteResult("u");
        assertEquals(RedisCircuitBreaker.State.OPEN, circuitBreaker.getState());

        cacheService.setFavoriteResult("user-1", "[]");

        verify(valueOps, never()).set(anyString(), anyString());
        assertTrue(metrics.getCircuitOpenSkips() >= 1L);
    }

    @Test
    void getKeywordDocumentFrequencies_returnsEmptyMapOnFailure() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.multiGet(anyString(), anyCollection()))
                .thenThrow(new RuntimeException("timeout"));

        Map<String, Integer> result = cacheService.getKeywordDocumentFrequencies(
                new HashSet<>(Collections.singletonList("crm")));

        assertTrue(result.isEmpty());
        assertEquals(1L, metrics.getErrors());
        assertEquals(1L, metrics.getMisses());
    }

    @Test
    void getSearchResult_servesL1WithoutHittingRedisOnSecondRead() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("search:lat=1.0&lon=2.0&keyword=crm")).thenReturn("[{\"id\":\"1\"}]");

        assertEquals("[{\"id\":\"1\"}]", cacheService.getSearchResult(1.0, 2.0, "crm"));
        assertEquals("[{\"id\":\"1\"}]", cacheService.getSearchResult(1.0, 2.0, "crm"));

        verify(valueOps, times(1)).get(anyString());
        assertEquals(1L, metrics.getL1Hits());
    }

    @Test
    void getSearchResult_servesStaleL1WhenRedisCircuitIsOpen() {
        java.util.concurrent.atomic.AtomicLong millis = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        LocalL1Cache l1 = new LocalL1Cache(10, 50L, 5_000L, LocalL1CacheTest.clock(millis));
        cacheService = new RedisCacheService(redis, circuitBreaker, metrics, l1,
                new RequestCoalescer(metrics),
                new MysqlFallbackLimiter(circuitBreaker, metrics, 32));

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("search:lat=1.0&lon=2.0&keyword=crm")).thenReturn("[1]");
        assertEquals("[1]", cacheService.getSearchResult(1.0, 2.0, "crm"));

        when(valueOps.get(anyString())).thenThrow(new RuntimeException("down"));
        cacheService.getFavoriteResult("trip");
        cacheService.getFavoriteResult("trip");
        assertEquals(RedisCircuitBreaker.State.OPEN, circuitBreaker.getState());

        millis.addAndGet(80L);
        assertEquals("[1]", cacheService.getSearchResult(1.0, 2.0, "crm"));
        assertTrue(metrics.getL1StaleHits() >= 1L);
    }

    @Test
    void getOrLoadFavoriteResult_coalescesConcurrentMysqlLoads() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cacheService.getOrLoadFavoriteResult("user-1", () -> {
                        loads.incrementAndGet();
                        Thread.sleep(60L);
                        return "[{\"id\":\"1\"}]";
                    });
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("[{\"id\":\"1\"}]", future.get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
            assertTrue(metrics.getCoalescedJoins() >= 7L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getOrLoadFavoriteResult_fillsL1WhenRedisIsDown() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("down"));
        cacheService.getFavoriteResult("trip");
        cacheService.getFavoriteResult("trip");
        assertEquals(RedisCircuitBreaker.State.OPEN, circuitBreaker.getState());

        String loaded = cacheService.getOrLoadFavoriteResult("user-1", () -> "[{\"id\":\"1\"}]");
        assertEquals("[{\"id\":\"1\"}]", loaded);
        assertEquals("[{\"id\":\"1\"}]", cacheService.getFavoriteResult("user-1"));
        verify(valueOps, never()).set(anyString(), anyString());
        assertEquals(1L, metrics.getMysqlFallbacks());
    }
}
