package com.ffaas.api.dto;

import com.ffaas.api.validation.ValidFlagRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@ValidFlagRules
public record UpdateFlagRequest(
        @Pattern(regexp = "^[a-z0-9][a-z0-9-_]{1,62}[a-z0-9]$")
        String key,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotNull Boolean enabled,
        @NotNull Boolean defaultState,
        @NotNull @Size(max = 50) List<@NotNull @Valid RuleDto> rules
) implements FlagRulesCarrier {
}
