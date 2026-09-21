package com.example.jobrec.recommendation;

import com.example.jobrec.db.MySQLConnection;
import com.example.jobrec.entity.Item;
import com.example.jobrec.service.RedisCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class RecommendationProfileService {
    private static final int TOP_KEYWORD_COUNT = 3;

    private final TFIDF tfidf = new TFIDF();
    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RecommendationProfileService(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    public List<String> getTopKeywords(String userId) {
        String cached = redisCacheService.getOrLoadRecommendationKeywords(userId, () -> {
            MySQLConnection connection = new MySQLConnection();
            Set<String> favoritedItemIds = connection.getFavoriteItemIds(userId);
            Map<String, Integer> termFrequencies = connection.getTermFrequenciesForItems(favoritedItemIds);
            connection.close();

            if (termFrequencies.isEmpty()) {
                return "";
            }

            int totalDocuments = getTotalDocuments();
            Map<String, Integer> documentFrequencies = getDocumentFrequencies(termFrequencies.keySet());

            Map<String, Double> tfidfScores = tfidf.computeScores(
                    termFrequencies, documentFrequencies, totalDocuments);
            List<Map.Entry<String, Double>> topKeywords =
                    tfidf.getTopKeywords(tfidfScores, TOP_KEYWORD_COUNT);

            List<String> keywords = new ArrayList<>();
            for (Map.Entry<String, Double> entry : topKeywords) {
                keywords.add(entry.getKey());
            }
            return String.join(",", keywords);
        });
        return parseKeywordList(cached);
    }

    public void invalidateCorpusCache() {
        redisCacheService.deleteCorpusCache();
    }

    public String getCachedSearchResult(double lat, double lon, String keyword) {
        return redisCacheService.getSearchResult(lat, lon, keyword);
    }

    public String getOrLoadSearchResult(double lat, double lon, String keyword, Supplier<List<Item>> loader) {
        return redisCacheService.getOrLoadSearchResult(lat, lon, keyword, () -> {
            List<Item> items = loader.get();
            try {
                return objectMapper.writeValueAsString(items);
            } catch (Exception e) {
                e.printStackTrace();
                return "[]";
            }
        });
    }

    public void cacheSearchResult(double lat, double lon, String keyword, List<Item> items) {
        try {
            redisCacheService.setSearchResult(lat, lon, keyword, objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<Item> parseItems(String cachedResult) {
        if (cachedResult == null || cachedResult.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return Arrays.asList(objectMapper.readValue(cachedResult, Item[].class));
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private int getTotalDocuments() {
        String cached = redisCacheService.getOrLoadCorpusTotalItems(() -> {
            MySQLConnection connection = new MySQLConnection();
            int totalDocuments = connection.getTotalItemCount();
            connection.close();
            return String.valueOf(totalDocuments);
        });
        return cached == null ? 0 : Integer.parseInt(cached);
    }

    private Map<String, Integer> getDocumentFrequencies(Set<String> keywords) {
        return redisCacheService.getOrLoadKeywordDocumentFrequencies(keywords, missingKeywords -> {
            MySQLConnection connection = new MySQLConnection();
            Map<String, Integer> loaded = connection.getDocumentFrequenciesForKeywords(missingKeywords);
            connection.close();
            return loaded;
        });
    }

    private List<String> parseKeywordList(String cached) {
        List<String> keywords = new ArrayList<>();
        if (cached == null || cached.isEmpty()) {
            return keywords;
        }
        for (String keyword : cached.split(",")) {
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }
}
