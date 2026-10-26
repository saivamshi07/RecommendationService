package com.platform.recommendationservice.service.scheduler;

import com.platform.recommendationservice.config.RecommendationProperties;
import com.platform.recommendationservice.service.FallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FallbackScheduler {

    private final FallbackService fallbackService;
    private final RecommendationProperties properties;

    /**
     * Scheduled job to recalculate global fallback recommendations.
     * Runs daily (default cron: 0 0 0 * * *).
     * Only executes if fallback is enabled in configuration.
     */
    @Scheduled(cron = "${recommendation.fallback.cron:0 0 0 * * *}")
    public void runDailyFallbackUpdate() {
        if (!properties.getFallback().isEnabled()) {
            log.info("Daily fallback job skipped because recommendation.fallback.enabled=false");
            return;
        }

        log.info("Running daily scheduled fallback recommendation update...");
        try {
            fallbackService.computeAndPersistGlobalFallback();
            log.info("Daily fallback recommendation update completed successfully.");
        } catch (Exception ex) {
            log.error("Error during daily fallback recommendation update: {}", ex.getMessage(), ex);
        }
    }
}
