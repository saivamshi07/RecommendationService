package com.platform.recommendationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.recommendationservice.config.RecommendationProperties;
import com.platform.recommendationservice.entity.FallbackRecommendation;
import com.platform.recommendationservice.repository.FallbackRecommendationRepository;
import com.platform.recommendationservice.repository.UserRatingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FallbackService {

    private final UserRatingRepository userRatingRepository;
    private final FallbackRecommendationRepository fallbackRecommendationRepository;
    private final RedisCacheService redisCacheService;
    private final RecommendationProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Retrieve fallback recommendations for a user, filtering out any items the user has already rated.
     */
    public List<String> getFallbackForUser(String userId, int limit) {
        List<String> rawFallback = getRawGlobalFallback();

        if (rawFallback.isEmpty()) {
            return Collections.emptyList();
        }

        // Fetch items already rated by this user to guarantee strict exclusion
        Set<String> ratedItemIds = userRatingRepository.findItemIdsByUserId(userId);

        return rawFallback.stream()
                .filter(itemId -> !ratedItemIds.contains(itemId))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Fetch global fallback recommendations: Try Redis -> Try DB Permanent Store -> Compute on-the-fly.
     */
    public List<String> getRawGlobalFallback() {
        // 1. Try Redis cache
        Optional<List<String>> cached = redisCacheService.getFallbackRecommendations();
        if (cached.isPresent() && !cached.get().isEmpty()) {
            log.debug("Fallback recommendations retrieved from Redis cache");
            return cached.get();
        }

        // 2. Try DB Permanent Store
        Optional<FallbackRecommendation> dbEntity = fallbackRecommendationRepository.findById(FallbackRecommendation.DEFAULT_FALLBACK_ID);
        if (dbEntity.isPresent() && !dbEntity.get().getRecommendedItemIds().isEmpty()) {
            List<String> dbItems = dbEntity.get().getRecommendedItemIds();
            log.debug("Fallback recommendations retrieved from Permanent Store DB");
            // Warm Redis cache
            redisCacheService.setFallbackRecommendations(dbItems, Duration.ofHours(properties.getCache().getTtlHours()));
            return dbItems;
        }

        // 3. Compute on-the-fly if empty and persist
        log.info("Computing global fallback recommendations on-the-fly");
        return computeAndPersistGlobalFallback();
    }

    /**
     * Compute top-rated / most popular items across the entire platform and save to DB + Redis.
     */
    public List<String> computeAndPersistGlobalFallback() {
        int fallbackLimit = properties.getFallback().getLimit();
        List<String> topItems = userRatingRepository.findTopRatedItemsOverall(PageRequest.of(0, fallbackLimit));

        if (!topItems.isEmpty()) {
            // Save to DB Permanent Store via atomic upsert
            try {
                String json = objectMapper.writeValueAsString(topItems);
                fallbackRecommendationRepository.upsert(FallbackRecommendation.DEFAULT_FALLBACK_ID, json, Instant.now());
            } catch (Exception e) {
                log.error("Error serializing fallback recommendations to JSON: {}", e.getMessage());
            }

            // Update Redis cache
            redisCacheService.setFallbackRecommendations(topItems, Duration.ofHours(properties.getCache().getTtlHours()));
        }

        return topItems;
    }
}
