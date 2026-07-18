package com.ffaas.config;

import com.ffaas.cache.EvaluationResultCache;
import com.ffaas.cache.FlagCache;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    @Bean
    public FlagCache flagCache(CacheProperties properties) {
        return new FlagCache(properties, Ticker.systemTicker());
    }

    @Bean
    public EvaluationResultCache evaluationResultCache(CacheProperties properties) {
        return new EvaluationResultCache(properties);
    }
}
