package com.ffaas.api.dto;

import com.ffaas.domain.Operator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConditionDto(
        @NotBlank @Size(max = 64) String attribute,
        @NotNull Operator operator,
        @NotNull Object value
) {
}
