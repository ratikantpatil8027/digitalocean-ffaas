package com.ffaas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ffaas.cache")
public record CacheProperties(
        Duration flagTtl,
        long flagMaxSize,
        Duration evaluationTtl,
        long evaluationMaxSize
) {
}
