package com.platform.recommendationservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCacheService {

    private static final String USER_REC_PREFIX = "recommendations:user:";
    private static final String FALLBACK_REC_KEY = "recommendations:fallback";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Retrieve cached recommendations for a specific user.
     * Returns Optional.empty() on cache miss or if Redis is unavailable.
     */
    public Optional<List<String>> getUserRecommendations(String userId) {
        String key = USER_REC_PREFIX + userId;
        return getListFromCache(key);
    }

    /**
     * Store recommendations for a user with TTL.
     */
    public void setUserRecommendations(String userId, List<String> itemIds, Duration ttl) {
        String key = USER_REC_PREFIX + userId;
        putListToCache(key, itemIds, ttl);
    }

    /**
     * Retrieve cached global fallback recommendations.
     */
    public Optional<List<String>> getFallbackRecommendations() {
        return getListFromCache(FALLBACK_REC_KEY);
    }

    /**
     * Store global fallback recommendations with TTL.
     */
    public void setFallbackRecommendations(List<String> itemIds, Duration ttl) {
        putListToCache(FALLBACK_REC_KEY, itemIds, ttl);
    }

    /**
     * Evict cached recommendations for a user.
     */
    public void evictUserRecommendations(String userId) {
        try {
            redisTemplate.delete(USER_REC_PREFIX + userId);
        } catch (Exception ex) {
            log.warn("Redis error evicting user cache for userId={}: {}", userId, ex.getMessage());
        }
    }

    private Optional<List<String>> getListFromCache(String key) {
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return Optional.empty();
            }
            if (raw instanceof List<?>) {
                List<String> result = objectMapper.convertValue(raw, new TypeReference<List<String>>() {});
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("Redis unavailable during read for key={}: {}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    private void putListToCache(String key, List<String> itemIds, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, itemIds, ttl);
        } catch (Exception ex) {
            log.warn("Redis unavailable during write for key={}: {}", key, ex.getMessage());
        }
    }
}
