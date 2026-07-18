package com.ffaas.api.dto;

import com.ffaas.engine.Reason;

import java.util.UUID;

public record EvaluateResponse(
        String flagKey,
        boolean enabled,
        Reason reason,
        UUID matchedRuleId
) {
}
