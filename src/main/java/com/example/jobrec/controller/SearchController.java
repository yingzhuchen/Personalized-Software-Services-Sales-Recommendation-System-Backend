package com.example.jobrec.controller;

import com.example.jobrec.entity.Item;
import com.example.jobrec.recommendation.RecommendationService;
import com.example.jobrec.service.HistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
public class SearchController {
    private final RecommendationService recommendationService;
    private final HistoryService historyService;

    public SearchController(RecommendationService recommendationService,
                            HistoryService historyService) {
        this.recommendationService = recommendationService;
        this.historyService = historyService;
    }

    @GetMapping("/search")
    public List<Item> search(@RequestParam("user_id") String userId,
                             @RequestParam("lat") double lat,
                             @RequestParam("lon") double lon,
                             @RequestParam(value = "keyword", required = false) String keyword,
                             HttpSession session) {
        SessionUtils.requireSession(session);

        Set<String> favoritedItemIds = new HashSet<>();
        for (Item favorite : historyService.getFavorites(userId)) {
            favoritedItemIds.add(favorite.getId());
        }

        List<Item> items = recommendationService.searchProducts(lat, lon, keyword);
        for (Item item : items) {
            item.setFavorite(favoritedItemIds.contains(item.getId()));
        }
        return items;
    }
}
