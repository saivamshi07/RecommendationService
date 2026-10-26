package com.platform.recommendationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.recommendationservice.config.RecommendationProperties;
import com.platform.recommendationservice.entity.UserRating;
import com.platform.recommendationservice.repository.UserRatingRepository;
import com.platform.recommendationservice.repository.UserRecommendationRepository;
import com.platform.recommendationservice.service.algorithm.CollaborativeFilteringEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AsyncRecommendationService {

    private final UserRatingRepository userRatingRepository;
    private final UserRecommendationRepository userRecommendationRepository;
    private final CollaborativeFilteringEngine cfEngine;
    private final RedisCacheService redisCacheService;
    private final RecommendationProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Asynchronously recalculate recommendations for a user (triggered on rating submission)
     * and update both Permanent Store (PostgreSQL) and Redis.
     */
    @Async("recommendationTaskExecutor")
    public void recalculateAndPersist(String userId) {
        log.info("Starting asynchronous recommendation recalculation for userId={}", userId);
        try {
            List<String> recommendations = computeRecommendationsForUser(userId, properties.getEngine().getTopN());
            persistAndCache(userId, recommendations);
            log.info("Completed asynchronous recalculation for userId={}, found {} items", userId, recommendations.size());
        } catch (Exception ex) {
            log.error("Failed asynchronous recommendation recalculation for userId={}: {}", userId, ex.getMessage(), ex);
        }
    }

    /**
     * Asynchronously save precalculated recommendations to Permanent Store and Redis.
     * (Called when GET recommendation recalculates on a cache miss).
     */
    @Async("recommendationTaskExecutor")
    public void persistAndCacheAsync(String userId, List<String> recommendations) {
        log.debug("Asynchronously saving recommendations to DB and Redis for userId={}", userId);
        try {
            persistAndCache(userId, recommendations);
        } catch (Exception ex) {
            log.error("Failed to asynchronously persist and cache recommendations for userId={}: {}", userId, ex.getMessage(), ex);
        }
    }

    /**
     * Synchronous / helper method to compute CF recommendations for a user.
     */
    public List<String> computeRecommendationsForUser(String userId, int limit) {
        List<UserRating> neighborRatings = userRatingRepository.findCandidateNeighborRatings(userId);
        List<UserRating> userRatings = userRatingRepository.findByUserId(userId);

        if (userRatings.isEmpty()) {
            return new ArrayList<>();
        }

        // Merge target user ratings with candidate neighbor ratings, eliminating duplicates
        Set<UserRating> combinedSet = new HashSet<>(neighborRatings);
        combinedSet.addAll(userRatings);

        return cfEngine.recommend(userId, limit, new ArrayList<>(combinedSet));
    }

    /**
     * Write-through: atomic upsert to Permanent Store (DB) and updates Redis cache with TTL.
     */
    public void persistAndCache(String userId, List<String> recommendations) {
        // 1. Permanent Store atomic upsert
        try {
            String json = objectMapper.writeValueAsString(recommendations != null ? recommendations : List.of());
            userRecommendationRepository.upsert(userId, json, Instant.now());
        } catch (Exception e) {
            log.error("Error serializing recommendations to JSON for userId={}: {}", userId, e.getMessage());
        }

        // 2. Redis Cache write
        if (recommendations != null && !recommendations.isEmpty()) {
            Duration ttl = Duration.ofHours(properties.getCache().getTtlHours());
            redisCacheService.setUserRecommendations(userId, recommendations, ttl);
        } else {
            redisCacheService.evictUserRecommendations(userId);
        }
    }
}
