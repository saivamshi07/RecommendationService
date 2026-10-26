package com.platform.recommendationservice.dto;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationResponse {

    private String userId;
    @Builder.Default
    private List<String> itemIds = new ArrayList<>();
    private int count;
    private String source;
}
