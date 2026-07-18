package com.ffaas.config;

import com.ffaas.engine.RuleEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    @Bean
    public RuleEvaluator ruleEvaluator() {
        return new RuleEvaluator();
    }
}
