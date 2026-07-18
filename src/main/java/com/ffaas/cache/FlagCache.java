package com.ffaas.cache;

import com.ffaas.config.CacheProperties;
import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Rule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * L1 flag-definition cache. Values are detached immutable snapshots (defensive copies).
 */
public class FlagCache {

    private final Cache<String, FeatureFlag> cache;

    public FlagCache(CacheProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    public FlagCache(CacheProperties properties, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.flagTtl())
                .maximumSize(properties.flagMaxSize())
                .ticker(ticker)
                .recordStats()
                .build();
    }

    public Optional<FeatureFlag> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    public void put(String key, FeatureFlag flag) {
        cache.put(key, snapshot(flag));
    }

    public void evict(String key) {
        cache.invalidate(key);
    }

    private static FeatureFlag snapshot(FeatureFlag source) {
        FeatureFlag copy = new FeatureFlag();
        copy.setKey(source.getKey());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setEnabled(source.isEnabled());
        copy.setDefaultState(source.isDefaultState());
        for (Rule rule : source.getRules()) {
            Rule ruleCopy = new Rule();
            copyRuleId(ruleCopy, rule.getId());
            ruleCopy.setPriority(rule.getPriority());
            ruleCopy.setServe(rule.isServe());
            ruleCopy.setRolloutPercentage(rule.getRolloutPercentage());
            List<Condition> conditions = rule.getConditions() == null
                    ? List.of()
                    : List.copyOf(rule.getConditions());
            ruleCopy.setConditions(new ArrayList<>(conditions));
            copy.addRule(ruleCopy);
        }
        return copy;
    }

    private static void copyRuleId(Rule target, UUID id) {
        if (id == null) {
            return;
        }
        try {
            Field field = Rule.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to copy rule id into cache snapshot", e);
        }
    }
}
