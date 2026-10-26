package com.platform.recommendationservice.repository;

import com.platform.recommendationservice.entity.UserRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface UserRecommendationRepository extends JpaRepository<UserRecommendation, String> {

    @Modifying
    @Query(value = "INSERT INTO user_recommendations (user_id, recommended_item_ids, calculated_at) " +
                   "VALUES (:userId, :itemIds, :calculatedAt) " +
                   "ON CONFLICT (user_id) DO UPDATE SET recommended_item_ids = EXCLUDED.recommended_item_ids, calculated_at = EXCLUDED.calculated_at",
           nativeQuery = true)
    void upsert(@Param("userId") String userId, @Param("itemIds") String itemIds, @Param("calculatedAt") Instant calculatedAt);
}
