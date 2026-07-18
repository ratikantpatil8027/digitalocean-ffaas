package com.ffaas.engine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RolloutBucketerTest {

    private final RolloutBucketer bucketer = new RolloutBucketer();

    @Test
    void shouldReturnSameBucketForSameFlagAndUserRepeatedly() {
        int first = bucketer.bucket("premium-dashboard", "user-123");
        assertThat(first).isBetween(0, 99);

        for (int i = 0; i < 1000; i++) {
            assertThat(bucketer.bucket("premium-dashboard", "user-123")).isEqualTo(first);
        }
    }

    @Test
    void shouldReturnDifferentBucketsAcrossFlagsForSameUser() {
        Set<Integer> buckets = new HashSet<>();
        buckets.add(bucketer.bucket("flag-a", "same-user"));
        buckets.add(bucketer.bucket("flag-b", "same-user"));
        buckets.add(bucketer.bucket("flag-c", "same-user"));
        buckets.add(bucketer.bucket("flag-d", "same-user"));
        buckets.add(bucketer.bucket("flag-e", "same-user"));

        assertThat(buckets.size())
                .as("same userId must land in different buckets across flags (spot-check)")
                .isGreaterThan(1);
    }
}
