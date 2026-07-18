package com.ffaas.api.dto;

import java.util.List;

/** Shared surface for class-level rule validation on create/update requests. */
public interface FlagRulesCarrier {
    List<RuleDto> rules();
}
