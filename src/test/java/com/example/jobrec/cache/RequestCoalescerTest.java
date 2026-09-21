package com.example.jobrec.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestCoalescerTest {
    @Test
    void concurrentCallersShareOneLoad() throws Exception {
        RedisCircuitBreaker circuitBreaker = new RedisCircuitBreaker(5, 30_000L, 1L);
        RedisCacheMetrics metrics = new RedisCacheMetrics(circuitBreaker);
        RequestCoalescer coalescer = new RequestCoalescer(metrics);

        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return coalescer.coalesce("search:crm", () -> {
                        loads.incrementAndGet();
                        sleep(80L);
                        return "[1]";
                    });
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            for (Future<String> future : futures) {
                assertEquals("[1]", future.get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, loads.get());
            assertEquals(7L, metrics.getCoalescedJoins());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loaderFailurePropagatesToWaiters() throws Exception {
        RedisCacheMetrics metrics = new RedisCacheMetrics(new RedisCircuitBreaker(5, 30_000L, 1L));
        RequestCoalescer coalescer = new RequestCoalescer(metrics);
        CountDownLatch inLoader = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> winner = pool.submit(() -> coalescer.coalesce("k", () -> {
                inLoader.countDown();
                while (metrics.getCoalescedJoins() == 0) {
                    Thread.yield();
                }
                throw new MysqlFallbackRejectedException("full");
            }));
            assertTrue(inLoader.await(2, TimeUnit.SECONDS));
            Future<String> waiter = pool.submit(() -> coalescer.coalesce("k", () -> "should-not-run"));

            ExecutionException winnerEx = assertThrows(ExecutionException.class,
                    () -> winner.get(2, TimeUnit.SECONDS));
            assertTrue(winnerEx.getCause() instanceof MysqlFallbackRejectedException);
            ExecutionException waiterEx = assertThrows(ExecutionException.class,
                    () -> waiter.get(2, TimeUnit.SECONDS));
            assertTrue(waiterEx.getCause() instanceof MysqlFallbackRejectedException);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
