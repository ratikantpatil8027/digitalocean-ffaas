package com.ffaas.api.dto;

import com.ffaas.api.validation.ValidEvaluateAttributes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Collections;
import java.util.Map;

@ValidEvaluateAttributes
public record EvaluateRequest(
        @NotBlank @Size(max = 128) String userId,
        Map<String, Object> attributes
) {
    public EvaluateRequest {
        if (attributes == null) {
            attributes = Collections.emptyMap();
        }
    }
}
