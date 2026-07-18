package com.ffaas.cache;

import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.config.CacheProperties;
import com.ffaas.engine.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationResultCacheTest {

    private EvaluationResultCache cache;

    @BeforeEach
    void setUp() {
        CacheProperties properties = new CacheProperties(
                Duration.ofSeconds(60),
                10_000,
                Duration.ofSeconds(30),
                10_000
        );
        cache = new EvaluationResultCache(properties);
    }

    @Test
    void shouldHashNumericAndStringAttributeValuesDifferently() {
        EvaluateResponse numeric = new EvaluateResponse("flag-a", true, Reason.RULE_MATCH, UUID.randomUUID());
        EvaluateResponse string = new EvaluateResponse("flag-a", false, Reason.DEFAULT, null);

        cache.put("flag-a", "user-1", Map.of("age", 34), numeric);
        cache.put("flag-a", "user-1", Map.of("age", "34"), string);

        assertThat(cache.get("flag-a", "user-1", Map.of("age", 34))).contains(numeric);
        assertThat(cache.get("flag-a", "user-1", Map.of("age", "34"))).contains(string);
        assertThat(cache.get("flag-a", "user-1", Map.of("age", 34)).orElseThrow().enabled()).isTrue();
        assertThat(cache.get("flag-a", "user-1", Map.of("age", "34")).orElseThrow().enabled()).isFalse();
    }

    @Test
    void shouldIsolateResultsBetweenDifferentContexts() {
        EvaluateResponse premium = new EvaluateResponse("flag-a", true, Reason.RULE_MATCH, UUID.randomUUID());
        EvaluateResponse free = new EvaluateResponse("flag-a", false, Reason.DEFAULT, null);

        cache.put("flag-a", "user-1", Map.of("tier", "premium"), premium);
        cache.put("flag-a", "user-1", Map.of("tier", "free"), free);

        assertThat(cache.get("flag-a", "user-1", Map.of("tier", "premium"))).contains(premium);
        assertThat(cache.get("flag-a", "user-1", Map.of("tier", "free"))).contains(free);
    }

    @Test
    void shouldPurgeKeyIndexOnEvictAllForFlag() {
        EvaluateResponse a1 = new EvaluateResponse("flag-a", true, Reason.DEFAULT, null);
        EvaluateResponse a2 = new EvaluateResponse("flag-a", false, Reason.DEFAULT, null);
        EvaluateResponse b1 = new EvaluateResponse("flag-b", true, Reason.DEFAULT, null);

        cache.put("flag-a", "u1", Map.of("x", 1), a1);
        cache.put("flag-a", "u2", Map.of("x", 2), a2);
        cache.put("flag-b", "u1", Map.of("x", 1), b1);

        cache.evictAllForFlag("flag-a");

        assertThat(cache.get("flag-a", "u1", Map.of("x", 1))).isEmpty();
        assertThat(cache.get("flag-a", "u2", Map.of("x", 2))).isEmpty();
        assertThat(cache.get("flag-b", "u1", Map.of("x", 1))).contains(b1);

        // Index purged: re-caching flag-a works; flag-b untouched; second eviction still clean.
        EvaluateResponse a3 = new EvaluateResponse("flag-a", true, Reason.RULE_MATCH, UUID.randomUUID());
        cache.put("flag-a", "u3", Map.of(), a3);
        cache.evictAllForFlag("flag-a");
        assertThat(cache.get("flag-a", "u3", Map.of())).isEmpty();
        assertThat(cache.get("flag-b", "u1", Map.of("x", 1))).contains(b1);
    }
}
