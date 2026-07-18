package com.ffaas.cache;

import com.ffaas.config.CacheProperties;
import com.ffaas.domain.FeatureFlag;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FlagCacheTest {

    private AtomicLong nanos;
    private FlagCache flagCache;

    @BeforeEach
    void setUp() {
        nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        CacheProperties properties = new CacheProperties(
                Duration.ofSeconds(60),
                10_000,
                Duration.ofSeconds(30),
                10_000
        );
        flagCache = new FlagCache(properties, ticker);
    }

    @Test
    void shouldExpireL1EntriesAfterTtl() {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("premium-dashboard");
        flag.setName("Premium");
        flag.setEnabled(true);
        flag.setDefaultState(false);

        flagCache.put("premium-dashboard", flag);
        assertThat(flagCache.get("premium-dashboard")).isPresent();

        nanos.addAndGet(Duration.ofSeconds(61).toNanos());

        assertThat(flagCache.get("premium-dashboard")).isEmpty();
    }
}
