package com.platform.recommendationservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "fallback_recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FallbackRecommendation {

    public static final Integer DEFAULT_FALLBACK_ID = 1;

    @Id
    private Integer id;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "recommended_item_ids", columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private List<String> recommendedItemIds = new ArrayList<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = Instant.now();
    }
}
