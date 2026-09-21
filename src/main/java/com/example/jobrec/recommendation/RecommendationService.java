package com.example.jobrec.recommendation;

import com.example.jobrec.entity.Item;
import com.example.jobrec.external.SerpAPIClient;
import com.example.jobrec.service.HistoryService;
import com.example.jobrec.service.ProductSearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class RecommendationService {
    private static final int MARKET_SUPPLEMENT_PER_KEYWORD = 3;

    private final RecommendationProfileService profileService;
    private final ProductSearchService productSearchService;
    private final HistoryService historyService;
    private final SerpAPIClient serpAPIClient = new SerpAPIClient();

    public RecommendationService(RecommendationProfileService profileService,
                                 ProductSearchService productSearchService,
                                 HistoryService historyService) {
        this.profileService = profileService;
        this.productSearchService = productSearchService;
        this.historyService = historyService;
    }

    /**
     * Recommendations prioritize INNOVA catalog products ranked by TF-IDF keywords.
     * SerpAPI market data is only used to supplement when catalog coverage is thin.
     */
    public List<Item> recommendItems(String userId, double lat, double lon) {
        List<Item> recommendedItems = new ArrayList<>();

        Set<String> favoritedItemIds = favoriteIds(userId);

        List<String> topKeywords = profileService.getTopKeywords(userId);
        if (topKeywords.isEmpty()) {
            return recommendedItems;
        }

        Set<String> visitedItemIds = new HashSet<>();
        List<Item> catalogMatches = productSearchService.searchCatalogByKeywords(topKeywords);
        for (Item item : catalogMatches) {
            if (!favoritedItemIds.contains(item.getId()) && visitedItemIds.add(item.getId())) {
                recommendedItems.add(item);
            }
        }

        for (String keyword : topKeywords) {
            List<Item> marketItems = serpAPIClient.search(lat, lon, keyword);
            int added = 0;
            for (Item item : marketItems) {
                if (added >= MARKET_SUPPLEMENT_PER_KEYWORD) {
                    break;
                }
                item.setSourceType(Item.SOURCE_MARKET);
                if (!favoritedItemIds.contains(item.getId()) && visitedItemIds.add(item.getId())) {
                    recommendedItems.add(item);
                    added++;
                }
            }
        }
        return recommendedItems;
    }

    public List<Item> searchProducts(double lat, double lon, String keyword) {
        String cacheKey = keyword == null ? "" : keyword;
        String cachedResult = profileService.getOrLoadSearchResult(lat, lon, cacheKey, () ->
                productSearchService.search(lat, lon, keyword));
        return profileService.parseItems(cachedResult);
    }

    private Set<String> favoriteIds(String userId) {
        Set<String> ids = new HashSet<>();
        for (Item item : historyService.getFavorites(userId)) {
            ids.add(item.getId());
        }
        return ids;
    }
}
