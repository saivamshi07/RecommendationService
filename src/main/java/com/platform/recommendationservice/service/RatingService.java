package com.platform.recommendationservice.service;

import com.platform.recommendationservice.dto.RatingRequest;
import com.platform.recommendationservice.dto.RatingResponse;
import com.platform.recommendationservice.entity.UserRating;
import com.platform.recommendationservice.repository.UserRatingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class RatingService {

    private final UserRatingRepository userRatingRepository;
    private final AsyncRecommendationService asyncRecommendationService;

    /**
     * Submit or update a user rating.
     * Upserts into PostgreSQL, returns immediate response,
     * and asynchronously triggers recommendation recalculation.
     */
    @Transactional
    public RatingResponse recordRating(RatingRequest request) {
        String userId = request.getUserId().trim();
        String itemId = request.getItemId().trim();
        BigDecimal rating = request.getRating().setScale(2, RoundingMode.HALF_UP);

        UserRating userRating = userRatingRepository.findByUserIdAndItemId(userId, itemId)
                .map(existing -> {
                    existing.setRating(rating);
                    existing.setUpdatedAt(Instant.now());
                    return existing;
                })
                .orElseGet(() -> UserRating.builder()
                        .userId(userId)
                        .itemId(itemId)
                        .rating(rating)
                        .build());

        UserRating saved = userRatingRepository.save(userRating);
        log.info("Saved rating for userId={}, itemId={}, rating={}", userId, itemId, rating);

        // Asynchronously recalculate recommendations for this user and update DB + Redis
        asyncRecommendationService.recalculateAndPersist(userId);

        return RatingResponse.builder()
                .id(saved.getId())
                .userId(saved.getUserId())
                .itemId(saved.getItemId())
                .rating(saved.getRating())
                .updatedAt(saved.getUpdatedAt())
                .build();
    }
}
