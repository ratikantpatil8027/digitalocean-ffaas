package com.ffaas.api.dto;

import java.util.List;
import java.util.UUID;

public record RuleResponse(
        UUID id,
        int priority,
        boolean serve,
        Integer rolloutPercentage,
        List<ConditionDto> conditions
) {
}
