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
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 flag-definition cache. Values are detached immutable snapshots (defensive copies).
 * Per-key epochs bump on {@link #evict(String)} so in-flight evaluate puts cannot re-populate
 * after write-through invalidation.
 */
public class FlagCache {

    private final Cache<String, FeatureFlag> cache;
    private final ConcurrentHashMap<String, Long> epochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

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

    public long epoch(String key) {
        return epochs.getOrDefault(key, 0L);
    }

    public Optional<FeatureFlag> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    /** Unconditional put for warm-path seeding (tests / cold populate at current epoch). */
    public void put(String key, FeatureFlag flag) {
        putIfEpoch(key, flag, epoch(key));
    }

    /**
     * Stores a snapshot only if {@code expectedEpoch} still matches — discards stale puts after eviction.
     *
     * @return true if the value was stored
     */
    public boolean putIfEpoch(String key, FeatureFlag flag, long expectedEpoch) {
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (epoch(key) != expectedEpoch) {
                return false;
            }
            cache.put(key, snapshot(flag));
            return true;
        }
    }

    public void evict(String key) {
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            epochs.merge(key, 1L, Long::sum);
            cache.invalidate(key);
        }
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
