package com.platform.recommendationservice.service.algorithm;

import com.platform.recommendationservice.config.RecommendationProperties;
import com.platform.recommendationservice.entity.UserRating;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollaborativeFilteringEngine {

    private final RecommendationProperties properties;

    /**
     * Compute User-Based Collaborative Filtering recommendations for a target user.
     * Guarantees that items already rated by targetUserId are strictly excluded.
     *
     * @param targetUserId    The user requesting recommendations
     * @param limit           Maximum number of item recommendations to return
     * @param allUserRatings  Ratings dataset (candidate neighbor ratings + target user ratings)
     * @return List of recommended itemIds sorted by highest predicted score
     */
    public List<String> recommend(String targetUserId, int limit, List<UserRating> allUserRatings) {
        if (allUserRatings == null || allUserRatings.isEmpty()) {
            return Collections.emptyList();
        }

        // Build User -> (Item -> Rating) Map
        Map<String, Map<String, Double>> userItemRatings = new HashMap<>();
        for (UserRating ur : allUserRatings) {
            userItemRatings
                    .computeIfAbsent(ur.getUserId(), k -> new HashMap<>())
                    .put(ur.getItemId(), ur.getRating().doubleValue());
        }

        Map<String, Double> targetRatings = userItemRatings.get(targetUserId);
        if (targetRatings == null || targetRatings.isEmpty()) {
            log.debug("Target user {} has no ratings. CF cannot compute recommendations.", targetUserId);
            return Collections.emptyList();
        }

        Set<String> alreadyRatedItems = targetRatings.keySet();

        // 1. Calculate Cosine Similarity with all other users
        Map<String, Double> userSimilarities = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : userItemRatings.entrySet()) {
            String otherUserId = entry.getKey();
            if (otherUserId.equals(targetUserId)) {
                continue;
            }
            double sim = computeCosineSimilarity(targetRatings, entry.getValue());
            if (sim > 0.0) {
                userSimilarities.put(otherUserId, sim);
            }
        }

        if (userSimilarities.isEmpty()) {
            log.debug("No similar neighbors found for target user {}.", targetUserId);
            return Collections.emptyList();
        }

        // 2. Select Top-K Neighbors
        int neighborK = properties.getEngine().getNeighborK();
        List<Map.Entry<String, Double>> topNeighbors = userSimilarities.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(neighborK)
                .toList();

        // 3. Predict Ratings for candidate unseen items
        // Map<ItemId, WeightedScoreSum> and Map<ItemId, SimSum>
        Map<String, Double> itemWeightedScore = new HashMap<>();
        Map<String, Double> itemSimSum = new HashMap<>();

        for (Map.Entry<String, Double> neighbor : topNeighbors) {
            String neighborId = neighbor.getKey();
            double similarity = neighbor.getValue();
            Map<String, Double> neighborRatings = userItemRatings.get(neighborId);

            if (neighborRatings == null) {
                continue;
            }

            for (Map.Entry<String, Double> itemRating : neighborRatings.entrySet()) {
                String itemId = itemRating.getKey();
                // Strictly exclude items already rated by target user
                if (alreadyRatedItems.contains(itemId)) {
                    continue;
                }

                double rating = itemRating.getValue();
                itemWeightedScore.merge(itemId, similarity * rating, Double::sum);
                itemSimSum.merge(itemId, similarity, Double::sum);
            }
        }

        if (itemWeightedScore.isEmpty()) {
            log.debug("No unrated candidate items found from neighbors for target user {}.", targetUserId);
            return Collections.emptyList();
        }

        // 4. Calculate predicted score for each item: score = sum(sim * rating) / sum(sim)
        Map<String, Double> predictedRatings = new HashMap<>();
        for (Map.Entry<String, Double> entry : itemWeightedScore.entrySet()) {
            String itemId = entry.getKey();
            double totalWeighted = entry.getValue();
            double totalSim = itemSimSum.getOrDefault(itemId, 1.0);
            predictedRatings.put(itemId, totalSim > 0 ? (totalWeighted / totalSim) : 0.0);
        }

        // 5. Rank and return top-N
        return predictedRatings.entrySet().stream()
                .sorted((e1, e2) -> {
                    int cmp = Double.compare(e2.getValue(), e1.getValue());
                    return cmp != 0 ? cmp : e1.getKey().compareTo(e2.getKey());
                })
                .map(Map.Entry::getKey)
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Compute Cosine Similarity between co-rated items of user A and user B.
     */
    private double computeCosineSimilarity(Map<String, Double> ratingsA, Map<String, Double> ratingsB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        boolean hasCoRated = false;
        for (Map.Entry<String, Double> entry : ratingsA.entrySet()) {
            String itemId = entry.getKey();
            Double ratingB = ratingsB.get(itemId);
            if (ratingB != null) {
                hasCoRated = true;
                double ratingA = entry.getValue();
                dotProduct += ratingA * ratingB;
                normA += ratingA * ratingA;
                normB += ratingB * ratingB;
            }
        }

        if (!hasCoRated || normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
