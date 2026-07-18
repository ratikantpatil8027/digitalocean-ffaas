package com.ffaas.cache;

import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.config.CacheProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 evaluation-result cache. Keys include a typed SHA-256 of the evaluation context.
 * A per-flag index supports {@link #evictAllForFlag(String)}; index entries are purged on eviction.
 */
public class EvaluationResultCache {

    private final Cache<String, EvaluateResponse> cache;
    private final ConcurrentHashMap<String, Set<String>> keysByFlag = new ConcurrentHashMap<>();

    public EvaluationResultCache(CacheProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.evaluationTtl())
                .maximumSize(properties.evaluationMaxSize())
                .recordStats()
                .removalListener((String key, EvaluateResponse value, RemovalCause cause) -> {
                    if (key != null) {
                        purgeIndexEntry(key);
                    }
                })
                .build();
    }

    public Optional<EvaluateResponse> get(String flagKey, String userId, Map<String, Object> attributes) {
        return Optional.ofNullable(cache.getIfPresent(cacheKey(flagKey, userId, attributes)));
    }

    public void put(String flagKey, String userId, Map<String, Object> attributes, EvaluateResponse response) {
        String key = cacheKey(flagKey, userId, attributes);
        keysByFlag.computeIfAbsent(flagKey, k -> ConcurrentHashMap.newKeySet()).add(key);
        cache.put(key, response);
    }

    public void evictAllForFlag(String flagKey) {
        Set<String> keys = keysByFlag.remove(flagKey);
        if (keys != null) {
            cache.invalidateAll(keys);
        }
    }

    static String cacheKey(String flagKey, String userId, Map<String, Object> attributes) {
        return flagKey + ":" + contextHash(userId, attributes);
    }

    static String contextHash(String userId, Map<String, Object> attributes) {
        StringBuilder normalized = new StringBuilder();
        normalized.append("userId=").append(userId);
        Map<String, Object> sorted = new TreeMap<>();
        if (attributes != null) {
            sorted.putAll(attributes);
        }
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            normalized.append('|')
                    .append(entry.getKey())
                    .append('=')
                    .append(typedRender(entry.getValue()));
        }
        return sha256Hex(normalized.toString());
    }

    private static String typedRender(Object value) {
        if (value instanceof Boolean b) {
            return "b:" + b;
        }
        if (value instanceof Number n) {
            return "n:" + n;
        }
        if (value instanceof String s) {
            return "s:" + s;
        }
        return "o:" + value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void purgeIndexEntry(String cacheKey) {
        int sep = cacheKey.indexOf(':');
        if (sep <= 0) {
            return;
        }
        String flagKey = cacheKey.substring(0, sep);
        Set<String> keys = keysByFlag.get(flagKey);
        if (keys != null) {
            keys.remove(cacheKey);
            if (keys.isEmpty()) {
                keysByFlag.remove(flagKey, keys);
            }
        }
    }
}
