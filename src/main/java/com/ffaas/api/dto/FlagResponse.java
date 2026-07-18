package com.ffaas.api.dto;

import java.time.Instant;
import java.util.List;

public record FlagResponse(
        String key,
        String name,
        String description,
        boolean enabled,
        boolean defaultState,
        List<RuleResponse> rules,
        Instant createdAt,
        Instant updatedAt
) {
}
