package com.platform.recommendationservice.repository;

import com.platform.recommendationservice.entity.FallbackRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface FallbackRecommendationRepository extends JpaRepository<FallbackRecommendation, Integer> {

    @Modifying
    @Query(value = "INSERT INTO fallback_recommendations (id, recommended_item_ids, updated_at) " +
                   "VALUES (:id, :itemIds, :updatedAt) " +
                   "ON CONFLICT (id) DO UPDATE SET recommended_item_ids = EXCLUDED.recommended_item_ids, updated_at = EXCLUDED.updated_at",
           nativeQuery = true)
    void upsert(@Param("id") Integer id, @Param("itemIds") String itemIds, @Param("updatedAt") Instant updatedAt);
}
