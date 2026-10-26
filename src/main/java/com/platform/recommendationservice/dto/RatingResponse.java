package com.platform.recommendationservice.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RatingResponse {

    private Long id;
    private String userId;
    private String itemId;
    private BigDecimal rating;
    private Instant updatedAt;
}
