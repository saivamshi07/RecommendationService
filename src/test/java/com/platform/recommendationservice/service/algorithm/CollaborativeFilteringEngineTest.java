package com.platform.recommendationservice.service.algorithm;

import com.platform.recommendationservice.config.RecommendationProperties;
import com.platform.recommendationservice.entity.UserRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollaborativeFilteringEngineTest {

    private CollaborativeFilteringEngine engine;
    private RecommendationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RecommendationProperties();
        properties.getEngine().setNeighborK(10);
        properties.getEngine().setTopN(5);
        engine = new CollaborativeFilteringEngine(properties);
    }

    @Test
    @DisplayName("Should recommend items liked by similar neighbors and strictly exclude already rated items")
    void testRecommendationsExcludeAlreadyRatedItems() {
        // Target User: user1 has rated movieA (4.50) and movieB (3.75)
        // Similar Neighbor: user2 has rated movieA (4.75), movieB (4.00), movieC (5.00), and movieD (2.00)
        // Less Similar Neighbor: user3 has rated movieA (1.00), movieB (1.50), movieE (4.50)
        Instant now = Instant.now();
        List<UserRating> dataset = new ArrayList<>();

        // user1 ratings
        dataset.add(UserRating.builder().userId("user1").itemId("movieA").rating(new BigDecimal("4.50")).createdAt(now).updatedAt(now).build());
        dataset.add(UserRating.builder().userId("user1").itemId("movieB").rating(new BigDecimal("3.75")).createdAt(now).updatedAt(now).build());

        // user2 ratings (very similar taste to user1)
        dataset.add(UserRating.builder().userId("user2").itemId("movieA").rating(new BigDecimal("4.75")).createdAt(now).updatedAt(now).build());
        dataset.add(UserRating.builder().userId("user2").itemId("movieB").rating(new BigDecimal("4.00")).createdAt(now).updatedAt(now).build());
        dataset.add(UserRating.builder().userId("user2").itemId("movieC").rating(new BigDecimal("5.00")).createdAt(now).updatedAt(now).build());
        dataset.add(UserRating.builder().userId("user2").itemId("movieD").rating(new BigDecimal("2.25")).createdAt(now).updatedAt(now).build());

        // user3 ratings
        dataset.add(UserRating.builder().userId("user3").itemId("movieA").rating(new BigDecimal("1.00")).createdAt(now).updatedAt(now).build());
        dataset.add(UserRating.builder().userId("user3").itemId("movieB").rating(new BigDecimal("1.50")).createdAt(now).updatedAt(now).build());
        dataset.add(UserRating.builder().userId("user3").itemId("movieE").rating(new BigDecimal("4.50")).createdAt(now).updatedAt(now).build());

        List<String> recommendations = engine.recommend("user1", 5, dataset);

        assertNotNull(recommendations);
        assertFalse(recommendations.isEmpty(), "Recommendations should not be empty");

        // CRUCIAL: user1 already rated movieA and movieB, so they MUST NOT appear in recommendations
        assertFalse(recommendations.contains("movieA"), "Already rated movieA must NOT be in recommendations");
        assertFalse(recommendations.contains("movieB"), "Already rated movieB must NOT be in recommendations");

        // movieC was rated 5.00 by very similar user2, so it should be ranked first
        assertEquals("movieC", recommendations.get(0), "movieC should be the top recommendation");
        assertTrue(recommendations.contains("movieD"), "movieD should be recommended");
    }

    @Test
    @DisplayName("Should return empty list if user has no ratings")
    void testUserWithNoRatingsReturnsEmpty() {
        List<UserRating> dataset = List.of(
                UserRating.builder().userId("otherUser").itemId("movieA").rating(new BigDecimal("4.50")).build()
        );

        List<String> recommendations = engine.recommend("coldUser", 5, dataset);
        assertTrue(recommendations.isEmpty(), "User with 0 ratings must return empty recommendations");
    }
}
