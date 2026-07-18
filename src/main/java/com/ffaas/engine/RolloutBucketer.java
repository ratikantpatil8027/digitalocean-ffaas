package com.ffaas.engine;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

/**
 * Deterministic per-user rollout bucket in {@code [0, 99]}.
 * Bucket = {@code abs(murmur3_32(flagKey + ":" + userId)) % 100}.
 */
public class RolloutBucketer {

    public int bucket(String flagKey, String userId) {
        int hash = Hashing.murmur3_32_fixed()
                .hashString(flagKey + ":" + userId, StandardCharsets.UTF_8)
                .asInt();
        // Math.abs(Integer.MIN_VALUE) stays negative — promote to long first.
        return (int) (Math.abs((long) hash) % 100);
    }
}
