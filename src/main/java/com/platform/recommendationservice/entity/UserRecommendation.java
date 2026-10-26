package com.platform.recommendationservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "user_recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRecommendation {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "recommended_item_ids", columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private List<String> recommendedItemIds = new ArrayList<>();

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.calculatedAt = Instant.now();
    }
}
