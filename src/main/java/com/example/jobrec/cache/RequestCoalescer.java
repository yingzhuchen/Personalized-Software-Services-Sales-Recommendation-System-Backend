package com.example.jobrec.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Singleflight: concurrent callers for the same key share one origin load.
 * Prevents a cache miss (or Redis outage) from turning into N identical MySQL queries.
 */
@Component
public class RequestCoalescer {
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();
    private final RedisCacheMetrics metrics;

    public RequestCoalescer(RedisCacheMetrics metrics) {
        this.metrics = metrics;
    }

    @SuppressWarnings("unchecked")
    public <T> T coalesce(String key, Supplier<T> loader) {
        CompletableFuture<Object> created = new CompletableFuture<>();
        CompletableFuture<Object> existing = inflight.putIfAbsent(key, created);
        if (existing != null) {
            metrics.recordCoalescedJoin();
            try {
                return (T) existing.join();
            } catch (CompletionException ex) {
                throw unwrap(ex);
            }
        }

        try {
            T result = loader.get();
            created.complete(result);
            return result;
        } catch (RuntimeException ex) {
            created.completeExceptionally(ex);
            throw ex;
        } catch (Exception ex) {
            created.completeExceptionally(ex);
            throw new RuntimeException(ex);
        } finally {
            inflight.remove(key, created);
        }
    }

    private static RuntimeException unwrap(CompletionException ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        return new RuntimeException(cause);
    }
}
