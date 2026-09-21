package com.example.jobrec.service;

import com.example.jobrec.cache.LocalL1Cache;
import com.example.jobrec.cache.MysqlFallbackLimiter;
import com.example.jobrec.cache.RedisCacheMetrics;
import com.example.jobrec.cache.RedisCircuitBreaker;
import com.example.jobrec.cache.RequestCoalescer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cache-aside access with L1 (process-local) + Redis L2.
 *
 * <p>Reads try L1 first, then Redis. On timeout/failure or an open circuit, Redis
 * reads return null/empty so callers can fall back to MySQL; Redis writes are skipped.
 *
 * <p>To prevent a Redis outage from stampeding MySQL:
 * <ul>
 *   <li>L1 serves fresh entries, and stale entries while the Redis circuit is OPEN</li>
 *   <li>{@link #getOrLoad} coalesces concurrent origin loads for the same key</li>
 *   <li>origin loads are bulkheaded while the circuit is OPEN</li>
 * </ul>
 */
@Service
public class RedisCacheService {
    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);

    private static final String SEARCH_KEY_TEMPLATE = "search:lat=%s&lon=%s&keyword=%s";
    private static final String FAVORITE_KEY_TEMPLATE = "history:userId=%s";
    private static final String RECOMMENDATION_KEY_TEMPLATE = "recommendation:keywords:userId=%s";
    private static final String CORPUS_TOTAL_ITEMS_KEY = "corpus:total_items";
    private static final String CORPUS_KEYWORD_DF_KEY = "corpus:keyword_df";

    private final StringRedisTemplate redis;
    private final RedisCircuitBreaker circuitBreaker;
    private final RedisCacheMetrics metrics;
    private final LocalL1Cache l1;
    private final RequestCoalescer coalescer;
    private final MysqlFallbackLimiter fallbackLimiter;

    public RedisCacheService(StringRedisTemplate redis,
                             RedisCircuitBreaker circuitBreaker,
                             RedisCacheMetrics metrics) {
        this(redis, circuitBreaker, metrics,
                new LocalL1Cache(10_000, 60_000L, 600_000L),
                new RequestCoalescer(metrics),
                new MysqlFallbackLimiter(circuitBreaker, metrics, 32));
    }

    @Autowired
    public RedisCacheService(StringRedisTemplate redis,
                             RedisCircuitBreaker circuitBreaker,
                             RedisCacheMetrics metrics,
                             LocalL1Cache l1,
                             RequestCoalescer coalescer,
                             MysqlFallbackLimiter fallbackLimiter) {
        this.redis = redis;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        this.l1 = l1;
        this.coalescer = coalescer;
        this.fallbackLimiter = fallbackLimiter;
    }

    public String getSearchResult(double lat, double lon, String keyword) {
        return getFromCaches(searchKey(lat, lon, keyword));
    }

    public void setSearchResult(double lat, double lon, String keyword, String value) {
        putToCaches(searchKey(lat, lon, keyword), value);
    }

    public String getOrLoadSearchResult(double lat, double lon, String keyword, Supplier<String> loader) {
        return getOrLoad(searchKey(lat, lon, keyword), loader);
    }

    public String getFavoriteResult(String userId) {
        return getFromCaches(favoriteKey(userId));
    }

    public void setFavoriteResult(String userId, String value) {
        putToCaches(favoriteKey(userId), value);
    }

    public String getOrLoadFavoriteResult(String userId, Supplier<String> loader) {
        return getOrLoad(favoriteKey(userId), loader);
    }

    public void deleteFavoriteResult(String userId) {
        deleteFromCaches(favoriteKey(userId));
    }

    public String getRecommendationKeywords(String userId) {
        return getFromCaches(recommendationKey(userId));
    }

    public void setRecommendationKeywords(String userId, String keywords) {
        putToCaches(recommendationKey(userId), keywords);
    }

    public String getOrLoadRecommendationKeywords(String userId, Supplier<String> loader) {
        return getOrLoad(recommendationKey(userId), loader);
    }

    public void deleteRecommendationKeywords(String userId) {
        deleteFromCaches(recommendationKey(userId));
    }

    public String getCorpusTotalItems() {
        return getFromCaches(CORPUS_TOTAL_ITEMS_KEY);
    }

    public void setCorpusTotalItems(String totalItems) {
        putToCaches(CORPUS_TOTAL_ITEMS_KEY, totalItems);
    }

    public String getOrLoadCorpusTotalItems(Supplier<String> loader) {
        return getOrLoad(CORPUS_TOTAL_ITEMS_KEY, loader);
    }

    public Map<String, Integer> getKeywordDocumentFrequencies(Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Integer> loaded = new HashMap<>();
        List<String> missing = new ArrayList<>();
        boolean allowStale = !circuitBreaker.isAvailable();
        for (String keyword : keywords) {
            String cached = readL1(dfL1Key(keyword), allowStale);
            if (cached != null) {
                loaded.put(keyword, Integer.parseInt(cached));
            } else {
                missing.add(keyword);
            }
        }
        if (missing.isEmpty()) {
            return loaded;
        }

        Map<String, Integer> fromRedis = readKeywordDocumentFrequenciesFromRedis(missing);
        loaded.putAll(fromRedis);
        return loaded;
    }

    public void setKeywordDocumentFrequencies(Map<String, Integer> frequencies) {
        if (frequencies == null || frequencies.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
            l1.put(dfL1Key(entry.getKey()), String.valueOf(entry.getValue()));
        }
        executeWrite(() -> {
            Map<String, String> hash = new HashMap<>();
            for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
                hash.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            redis.opsForHash().putAll(CORPUS_KEYWORD_DF_KEY, hash);
        });
    }

    public Map<String, Integer> getOrLoadKeywordDocumentFrequencies(
            Set<String> keywords,
            Function<List<String>, Map<String, Integer>> missingLoader) {
        Map<String, Integer> found = getKeywordDocumentFrequencies(keywords);
        List<String> missing = missingKeywords(keywords, found);
        if (missing.isEmpty()) {
            return found;
        }

        return coalescer.coalesce(CORPUS_KEYWORD_DF_KEY, () -> {
            Map<String, Integer> again = getKeywordDocumentFrequencies(keywords);
            List<String> stillMissing = missingKeywords(keywords, again);
            if (stillMissing.isEmpty()) {
                return again;
            }
            Map<String, Integer> loaded = fallbackLimiter.run(() -> missingLoader.apply(stillMissing));
            if (loaded != null && !loaded.isEmpty()) {
                setKeywordDocumentFrequencies(loaded);
                again.putAll(loaded);
            }
            return again;
        });
    }

    public void deleteCorpusCache() {
        l1.invalidate(CORPUS_TOTAL_ITEMS_KEY);
        l1.invalidatePrefix(CORPUS_KEYWORD_DF_KEY);
        executeWrite(() -> redis.delete(Arrays.asList(CORPUS_TOTAL_ITEMS_KEY, CORPUS_KEYWORD_DF_KEY)));
    }

    public void invalidateUserCaches(String userId) {
        deleteFavoriteResult(userId);
        deleteRecommendationKeywords(userId);
    }

    String getOrLoad(String key, Supplier<String> loader) {
        String cached = getFromCaches(key);
        if (cached != null) {
            return cached;
        }
        return coalescer.coalesce(key, () -> {
            String again = getFromCaches(key);
            if (again != null) {
                return again;
            }
            String loaded = fallbackLimiter.run(loader);
            if (loaded != null && !loaded.isEmpty()) {
                putToCaches(key, loaded);
            }
            return loaded;
        });
    }

    private String getFromCaches(String key) {
        boolean allowStale = !circuitBreaker.isAvailable();
        String local = readL1(key, allowStale);
        if (local != null) {
            return local;
        }
        String remote = executeRead(() -> redis.opsForValue().get(key));
        if (remote != null) {
            l1.put(key, remote);
        }
        return remote;
    }

    private void putToCaches(String key, String value) {
        l1.put(key, value);
        executeWrite(() -> redis.opsForValue().set(key, value));
    }

    private void deleteFromCaches(String key) {
        l1.invalidate(key);
        executeWrite(() -> redis.delete(key));
    }

    private String readL1(String key, boolean allowStale) {
        String local = l1.get(key, allowStale);
        if (local == null) {
            metrics.recordL1Miss();
            return null;
        }
        if (allowStale && l1.get(key, false) == null) {
            metrics.recordL1StaleHit();
        } else {
            metrics.recordL1Hit();
        }
        return local;
    }

    private Map<String, Integer> readKeywordDocumentFrequenciesFromRedis(List<String> keywords) {
        Map<String, Integer> frequencies = executeRead(() -> {
            List<Object> values = redis.opsForHash().multiGet(CORPUS_KEYWORD_DF_KEY, new ArrayList<>(keywords));
            Map<String, String> hash = new HashMap<>();
            int index = 0;
            for (String keyword : keywords) {
                Object value = values.get(index++);
                if (value != null) {
                    hash.put(keyword, value.toString());
                }
            }
            return hash.isEmpty() ? null : hash;
        });

        Map<String, Integer> loaded = new HashMap<>();
        if (frequencies == null) {
            return loaded;
        }
        for (Map.Entry<String, String> entry : frequencies.entrySet()) {
            loaded.put(entry.getKey(), Integer.parseInt(entry.getValue()));
            l1.put(dfL1Key(entry.getKey()), entry.getValue());
        }
        return loaded;
    }

    private static List<String> missingKeywords(Set<String> keywords, Map<String, Integer> found) {
        List<String> missing = new ArrayList<>();
        for (String keyword : keywords) {
            if (!found.containsKey(keyword)) {
                missing.add(keyword);
            }
        }
        return missing;
    }

    private static String searchKey(double lat, double lon, String keyword) {
        return String.format(SEARCH_KEY_TEMPLATE, lat, lon, keyword);
    }

    private static String favoriteKey(String userId) {
        return String.format(FAVORITE_KEY_TEMPLATE, userId);
    }

    private static String recommendationKey(String userId) {
        return String.format(RECOMMENDATION_KEY_TEMPLATE, userId);
    }

    private static String dfL1Key(String keyword) {
        return CORPUS_KEYWORD_DF_KEY + ":" + keyword;
    }

    private <T> T executeRead(Supplier<T> action) {
        if (!circuitBreaker.allowRequest()) {
            metrics.recordCircuitOpenSkip();
            metrics.recordMiss();
            return null;
        }
        try {
            T result = action.get();
            circuitBreaker.recordSuccess();
            if (result == null) {
                metrics.recordMiss();
            } else {
                metrics.recordHit();
            }
            return result;
        } catch (RuntimeException ex) {
            circuitBreaker.recordFailure();
            metrics.recordError();
            metrics.recordMiss();
            logger.warn("Redis read failed; failing open to MySQL. cause={}", ex.toString());
            return null;
        }
    }

    private void executeWrite(Runnable action) {
        if (!circuitBreaker.allowRequest()) {
            metrics.recordCircuitOpenSkip();
            return;
        }
        try {
            action.run();
            circuitBreaker.recordSuccess();
        } catch (RuntimeException ex) {
            circuitBreaker.recordFailure();
            metrics.recordError();
            logger.warn("Redis write failed; skipping cache update. cause={}", ex.toString());
        }
    }
}
