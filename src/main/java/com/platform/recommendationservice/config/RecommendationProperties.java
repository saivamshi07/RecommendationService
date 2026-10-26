package com.platform.recommendationservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "recommendation")
@Getter
@Setter
public class RecommendationProperties {

    private Engine engine = new Engine();
    private Cache cache = new Cache();
    private Fallback fallback = new Fallback();

    @Getter
    @Setter
    public static class Engine {
        private int topN = 10;
        private int neighborK = 20;
    }

    @Getter
    @Setter
    public static class Cache {
        private long ttlHours = 24;
    }

    @Getter
    @Setter
    public static class Fallback {
        /**
         * Fallback policy toggle. Default is false (NO fallback).
         */
        private boolean enabled = false;
        private String cron = "0 0 0 * * *";
        private int limit = 20;
    }
}
