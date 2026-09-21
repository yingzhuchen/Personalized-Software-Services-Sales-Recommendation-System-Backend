package com.example.jobrec.recommendation;

import com.example.jobrec.service.RedisCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationProfileServiceTest {
    @Mock
    private RedisCacheService redisCacheService;

    @InjectMocks
    private RecommendationProfileService profileService;

    @Test
    void getTopKeywords_returnsCachedKeywordsWithoutDatabaseLookup() {
        when(redisCacheService.getOrLoadRecommendationKeywords(eq("user-1"), any()))
                .thenReturn("crm,analytics,ai");

        List<String> keywords = profileService.getTopKeywords("user-1");

        assertEquals(Arrays.asList("crm", "analytics", "ai"), keywords);
    }

    @Test
    void getCachedSearchResult_readsRedisSearchCache() {
        when(redisCacheService.getSearchResult(1.0, 2.0, "crm")).thenReturn("[{\"id\":\"innova-crm\"}]");

        String cached = profileService.getCachedSearchResult(1.0, 2.0, "crm");

        assertEquals("[{\"id\":\"innova-crm\"}]", cached);
        verify(redisCacheService).getSearchResult(1.0, 2.0, "crm");
    }
}
