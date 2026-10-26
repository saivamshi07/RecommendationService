package com.platform.recommendationservice;

import com.platform.recommendationservice.config.RecommendationProperties;
import com.platform.recommendationservice.dto.RatingRequest;
import com.platform.recommendationservice.dto.RatingResponse;
import com.platform.recommendationservice.dto.RecommendationResponse;
import com.platform.recommendationservice.entity.UserRecommendation;
import com.platform.recommendationservice.repository.FallbackRecommendationRepository;
import com.platform.recommendationservice.repository.UserRatingRepository;
import com.platform.recommendationservice.repository.UserRecommendationRepository;
import com.platform.recommendationservice.service.RatingService;
import com.platform.recommendationservice.service.RecommendationService;
import com.platform.recommendationservice.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RecommendationIntegrationTest {

    @Autowired
    private RatingService ratingService;

    @Autowired
    private RecommendationService recommendationService;

    @Autowired
    private UserRatingRepository userRatingRepository;

    @Autowired
    private UserRecommendationRepository userRecommendationRepository;

    @Autowired
    private FallbackRecommendationRepository fallbackRecommendationRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private RecommendationProperties properties;

    @BeforeEach
    void cleanUp() {
        userRatingRepository.deleteAll();
        userRecommendationRepository.deleteAll();
        fallbackRecommendationRepository.deleteAll();
    }

    @Test
    @DisplayName("Test rating submission, 2-digit fraction precision, and upsert")
    void testRatingSubmissionAndUpsert() {
        String userId = "user-101";
        String itemId = "movie-99";

        // 1. Initial rating: 3.75
        RatingResponse res1 = ratingService.recordRating(RatingRequest.builder()
                .userId(userId)
                .itemId(itemId)
                .rating(new BigDecimal("3.75"))
                .build());

        assertEquals(new BigDecimal("3.75"), res1.getRating());
        assertEquals(userId, res1.getUserId());
        assertEquals(itemId, res1.getItemId());

        // 2. Update rating: 4.50 (Upsert)
        RatingResponse res2 = ratingService.recordRating(RatingRequest.builder()
                .userId(userId)
                .itemId(itemId)
                .rating(new BigDecimal("4.50"))
                .build());

        assertEquals(res1.getId(), res2.getId(), "Upsert should preserve the same entity ID");
        assertEquals(new BigDecimal("4.50"), res2.getRating());
        assertEquals(1, userRatingRepository.count());
    }

    @Test
    @DisplayName("Test end-to-end recommendation flow: Miss -> Calculate -> Cache -> Hit")
    void testRecommendationFlowCacheMissThenHit() throws InterruptedException {
        String userA = "user-A";
        String userB = "user-B";

        // Seed ratings:
        // userA likes m1 (4.50), m2 (4.00)
        // userB likes m1 (4.75), m2 (4.25), m3 (5.00), m4 (3.50)
        ratingService.recordRating(new RatingRequest(userA, "m1", new BigDecimal("4.50")));
        ratingService.recordRating(new RatingRequest(userA, "m2", new BigDecimal("4.00")));

        ratingService.recordRating(new RatingRequest(userB, "m1", new BigDecimal("4.75")));
        ratingService.recordRating(new RatingRequest(userB, "m2", new BigDecimal("4.25")));
        ratingService.recordRating(new RatingRequest(userB, "m3", new BigDecimal("5.00")));
        ratingService.recordRating(new RatingRequest(userB, "m4", new BigDecimal("3.50")));

        // Evict any cache for userA to simulate initial Redis MISS
        redisCacheService.evictUserRecommendations(userA);

        // 1. Call GET recommendations on Redis MISS
        RecommendationResponse response1 = recommendationService.getRecommendations(userA, 5);

        assertNotNull(response1);
        assertEquals("RECALCULATED", response1.getSource());
        assertTrue(response1.getItemIds().contains("m3"));
        assertTrue(response1.getItemIds().contains("m4"));
        // Strictly exclude already rated items
        assertFalse(response1.getItemIds().contains("m1"));
        assertFalse(response1.getItemIds().contains("m2"));

        // Wait briefly for async write to complete
        Thread.sleep(800);

        // Verify permanent store (PostgreSQL) has the recommendation
        Optional<UserRecommendation> dbEntity = userRecommendationRepository.findById(userA);
        assertTrue(dbEntity.isPresent(), "Permanent store should persist recommendations");
        assertTrue(dbEntity.get().getRecommendedItemIds().contains("m3"));

        // 2. Call GET recommendations again -> Should be REDIS_CACHE_HIT
        RecommendationResponse response2 = recommendationService.getRecommendations(userA, 5);
        assertEquals("REDIS_CACHE_HIT", response2.getSource());
        assertEquals(response1.getItemIds(), response2.getItemIds());
    }

    @Test
    @DisplayName("Test fallback policy: NO returns empty, YES returns fallback excluding rated items")
    void testFallbackPolicy() {
        String coldUser = "cold-user-1";

        // Seed some popular items in system rated by other users
        ratingService.recordRating(new RatingRequest("userX", "pop-1", new BigDecimal("5.00")));
        ratingService.recordRating(new RatingRequest("userY", "pop-1", new BigDecimal("4.80")));
        ratingService.recordRating(new RatingRequest("userX", "pop-2", new BigDecimal("4.50")));
        ratingService.recordRating(new RatingRequest("userY", "pop-3", new BigDecimal("4.20")));

        // Give coldUser 1 rating for an isolated movie that NO other user rated (so coldUser has 0 CF neighbors)
        // AND 1 rating for pop-1 so we verify strict exclusion in fallback
        ratingService.recordRating(new RatingRequest(coldUser, "isolated-movie", new BigDecimal("3.50")));
        ratingService.recordRating(new RatingRequest(coldUser, "pop-1", new BigDecimal("4.00")));

        redisCacheService.evictUserRecommendations(coldUser);

        // Case A: Fallback policy = false (NO) - default
        properties.getFallback().setEnabled(false);
        // Note: coldUser rated pop-1 and userX, userY also rated pop-1, so CF actually produces pop-2, pop-3.
        // Let's create an entirely unrated cold user with 0 co-rated neighbors
        String trueColdUser = "true-cold-user";
        ratingService.recordRating(new RatingRequest(trueColdUser, "completely-isolated-movie", new BigDecimal("4.00")));
        redisCacheService.evictUserRecommendations(trueColdUser);

        RecommendationResponse respNoFallback = recommendationService.getRecommendations(trueColdUser, 5);
        assertTrue(respNoFallback.getItemIds().isEmpty(), "When fallback is NO, 0 CF results must return empty list []");
        assertEquals("EMPTY", respNoFallback.getSource());

        // Case B: Fallback policy = true (YES)
        properties.getFallback().setEnabled(true);
        RecommendationResponse respWithFallback = recommendationService.getRecommendations(trueColdUser, 5);

        assertFalse(respWithFallback.getItemIds().isEmpty(), "When fallback is YES, fallback items must be returned");
        assertEquals("FALLBACK", respWithFallback.getSource());
        // Verify items returned
        assertTrue(respWithFallback.getItemIds().contains("pop-1"));
        assertTrue(respWithFallback.getItemIds().contains("pop-2"));

        // Reset
        properties.getFallback().setEnabled(false);
    }
}
