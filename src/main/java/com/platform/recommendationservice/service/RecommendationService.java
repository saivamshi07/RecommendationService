package com.platform.recommendationservice.service;

import com.platform.recommendationservice.config.RecommendationProperties;
import com.platform.recommendationservice.dto.RecommendationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationService {

    private final RedisCacheService redisCacheService;
    private final AsyncRecommendationService asyncRecommendationService;
    private final FallbackService fallbackService;
    private final RecommendationProperties properties;

    /**
     * Retrieve recommendations for a user:
     * 1. If Redis hit: return recommendations.
     * 2. If Redis miss (or down): recalculate on-demand via Collaborative Filtering.
     *    - If recalculated > 0: return and asynchronously update permanent store + Redis.
     *    - If recalculated == 0:
     *      - If fallback policy is YES: fetch fallback (Redis -> DB -> filter rated items).
     *      - If fallback policy is NO: return empty list [].
     */
    public RecommendationResponse getRecommendations(String userId, Integer limitParam) {
        int limit = (limitParam != null && limitParam > 0) ? limitParam : properties.getEngine().getTopN();
        String trimmedUserId = userId.trim();

        // 1. Try Redis cache
        Optional<List<String>> cached = redisCacheService.getUserRecommendations(trimmedUserId);
        if (cached.isPresent() && !cached.get().isEmpty()) {
            List<String> items = cached.get();
            if (items.size() > limit) {
                items = items.subList(0, limit);
            }
            log.debug("Redis cache HIT for userId={}", trimmedUserId);
            return RecommendationResponse.builder()
                    .userId(trimmedUserId)
                    .itemIds(items)
                    .count(items.size())
                    .source("REDIS_CACHE_HIT")
                    .build();
        }

        // 2. Redis MISS (or down) -> Recalculate on-demand via Collaborative Filtering
        log.info("Redis cache MISS for userId={}, calculating on-demand via CF", trimmedUserId);
        List<String> calculated = asyncRecommendationService.computeRecommendationsForUser(trimmedUserId, limit);

        if (!calculated.isEmpty()) {
            // Asynchronously update permanent store and Redis
            asyncRecommendationService.persistAndCacheAsync(trimmedUserId, calculated);

            return RecommendationResponse.builder()
                    .userId(trimmedUserId)
                    .itemIds(calculated)
                    .count(calculated.size())
                    .source("RECALCULATED")
                    .build();
        }

        // 3. Recalculate returned 0 items -> Check fallback policy
        boolean fallbackEnabled = properties.getFallback().isEnabled();
        if (!fallbackEnabled) {
            log.info("Recalculate yielded 0 items and fallback is disabled. Returning empty list for userId={}", trimmedUserId);
            return RecommendationResponse.builder()
                    .userId(trimmedUserId)
                    .itemIds(Collections.emptyList())
                    .count(0)
                    .source("EMPTY")
                    .build();
        }

        // Fallback is YES: fetch from Redis -> DB fallback -> filter user rated
        log.info("Recalculate yielded 0 items, fetching fallback recommendations for userId={}", trimmedUserId);
        List<String> fallbackItems = fallbackService.getFallbackForUser(trimmedUserId, limit);

        return RecommendationResponse.builder()
                .userId(trimmedUserId)
                .itemIds(fallbackItems)
                .count(fallbackItems.size())
                .source("FALLBACK")
                .build();
    }
}
