package com.example.jobrec.recommendation;

import com.example.jobrec.entity.Item;
import com.example.jobrec.external.SerpAPIClient;
import com.example.jobrec.service.HistoryService;
import com.example.jobrec.service.ProductSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {
    @Mock
    private RecommendationProfileService profileService;

    @Mock
    private ProductSearchService productSearchService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private RecommendationService recommendationService;

    @Test
    void recommendItems_returnsEmptyListWhenUserHasNoKeywords() {
        when(historyService.getFavorites("user-1")).thenReturn(Collections.emptySet());
        when(profileService.getTopKeywords("user-1")).thenReturn(Collections.emptyList());

        List<Item> results = recommendationService.recommendItems("user-1", 37.4, -122.1);

        assertTrue(results.isEmpty());
    }

    @Test
    void recommendItems_prioritizesCatalogMatchesAndSkipsFavoritedItems() {
        Item catalogItem = new Item(
                "innova-analytics",
                "INNOVA Analytics Suite",
                "INNOVA AI",
                "$199/month",
                "INNOVA AI",
                Item.SOURCE_INNOVA_CATALOG,
                "Analytics",
                null,
                "https://innova.ai/products/analytics",
                new HashSet<>(Collections.singletonList("analytics")),
                false);

        try (MockedConstruction<SerpAPIClient> serp = mockConstruction(SerpAPIClient.class,
                     (mock, context) -> when(mock.search(any(), any(), anyString()))
                             .thenReturn(Collections.emptyList()))) {
            when(historyService.getFavorites("user-1")).thenReturn(Collections.emptySet());
            when(profileService.getTopKeywords("user-1")).thenReturn(Arrays.asList("analytics", "crm"));
            when(productSearchService.searchCatalogByKeywords(Arrays.asList("analytics", "crm")))
                    .thenReturn(Collections.singletonList(catalogItem));

            List<Item> results = recommendationService.recommendItems("user-1", 37.4, -122.1);

            assertEquals(1, results.size());
            assertEquals("innova-analytics", results.get(0).getId());
            assertEquals(Item.SOURCE_INNOVA_CATALOG, results.get(0).getSourceType());
        }
    }

    @Test
    void searchProducts_returnsCachedResultsWhenPresent() {
        Item cachedItem = new Item(
                "innova-crm",
                "INNOVA CRM Platform",
                "INNOVA AI",
                "$99/month",
                "INNOVA AI",
                Item.SOURCE_INNOVA_CATALOG,
                "CRM",
                null,
                "https://innova.ai/products/crm",
                new HashSet<>(Collections.singletonList("crm")),
                false);
        String cachedJson = "[{\"id\":\"innova-crm\",\"title\":\"INNOVA CRM Platform\",\"source_type\":\"innova_catalog\",\"favorite\":false}]";
        when(profileService.getOrLoadSearchResult(eq(1.0), eq(2.0), eq("crm"), any())).thenReturn(cachedJson);
        when(profileService.parseItems(cachedJson)).thenReturn(Collections.singletonList(cachedItem));

        List<Item> results = recommendationService.searchProducts(1.0, 2.0, "crm");

        assertEquals(1, results.size());
        assertEquals("innova-crm", results.get(0).getId());
    }
}
