package com.platform.recommendationservice.repository;

import com.platform.recommendationservice.entity.UserRating;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface UserRatingRepository extends JpaRepository<UserRating, Long> {

    Optional<UserRating> findByUserIdAndItemId(String userId, String itemId);

    List<UserRating> findByUserId(String userId);

    @Query("SELECT r.itemId FROM UserRating r WHERE r.userId = :userId")
    Set<String> findItemIdsByUserId(@Param("userId") String userId);

    @Query("SELECT r.itemId FROM UserRating r GROUP BY r.itemId ORDER BY AVG(r.rating) DESC, COUNT(r.id) DESC")
    List<String> findTopRatedItemsOverall(Pageable pageable);

    @Query("SELECT r FROM UserRating r WHERE r.userId IN (" +
           "  SELECT DISTINCT r2.userId FROM UserRating r2 WHERE r2.itemId IN (" +
           "    SELECT r1.itemId FROM UserRating r1 WHERE r1.userId = :userId" +
           "  )" +
           ")")
    List<UserRating> findCandidateNeighborRatings(@Param("userId") String userId);
}
