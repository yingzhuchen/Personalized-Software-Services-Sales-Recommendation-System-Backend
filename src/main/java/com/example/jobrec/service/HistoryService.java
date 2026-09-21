package com.example.jobrec.service;

import com.example.jobrec.db.MySQLConnection;
import com.example.jobrec.entity.Item;
import com.example.jobrec.recommendation.RecommendationProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class HistoryService {
    private final RedisCacheService redisCacheService;
    private final RecommendationProfileService recommendationProfileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HistoryService(RedisCacheService redisCacheService,
                          RecommendationProfileService recommendationProfileService) {
        this.redisCacheService = redisCacheService;
        this.recommendationProfileService = recommendationProfileService;
    }

    public void addFavorite(String userId, Item item) {
        MySQLConnection connection = new MySQLConnection();
        connection.setFavoriteItems(userId, item);
        connection.close();

        redisCacheService.invalidateUserCaches(userId);
        recommendationProfileService.invalidateCorpusCache();
    }

    public Set<Item> getFavorites(String userId) {
        String cachedResult = redisCacheService.getOrLoadFavoriteResult(userId, () -> {
            MySQLConnection connection = new MySQLConnection();
            Set<Item> items = connection.getFavoriteItems(userId);
            connection.close();
            try {
                return objectMapper.writeValueAsString(items);
            } catch (Exception e) {
                e.printStackTrace();
                return "[]";
            }
        });
        if (cachedResult == null || cachedResult.isEmpty()) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(Arrays.asList(objectMapper.readValue(cachedResult, Item[].class)));
        } catch (Exception e) {
            e.printStackTrace();
            return new HashSet<>();
        }
    }

    public void removeFavorite(String userId, String itemId) {
        MySQLConnection connection = new MySQLConnection();
        connection.unsetFavoriteItems(userId, itemId);
        connection.close();

        redisCacheService.invalidateUserCaches(userId);
    }
}
