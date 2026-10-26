package com.platform.recommendationservice.controller;

import com.platform.recommendationservice.dto.RecommendationResponse;
import com.platform.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/{userId}")
    public ResponseEntity<RecommendationResponse> getRecommendations(
            @PathVariable("userId") String userId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        RecommendationResponse response = recommendationService.getRecommendations(userId, limit);
        return ResponseEntity.ok(response);
    }
}
