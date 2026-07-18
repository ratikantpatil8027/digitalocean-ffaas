package com.ffaas.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RuleDto(
        @Min(0) int priority,
        @NotNull Boolean serve,
        @NotNull @Size(min = 1, max = 20) List<@Valid ConditionDto> conditions,
        @Min(0) @Max(100) Integer rolloutPercentage
) {
}
