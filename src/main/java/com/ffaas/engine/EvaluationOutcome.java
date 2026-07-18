package com.ffaas.engine;

import java.util.UUID;

public record EvaluationOutcome(boolean enabled, Reason reason, UUID matchedRuleId) {
}
