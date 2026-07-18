package com.ffaas.service;

import com.ffaas.api.dto.ConditionDto;
import com.ffaas.api.dto.CreateFlagRequest;
import com.ffaas.api.dto.FlagResponse;
import com.ffaas.api.dto.PagedResponse;
import com.ffaas.api.dto.RuleDto;
import com.ffaas.api.dto.RuleResponse;
import com.ffaas.api.dto.UpdateFlagRequest;
import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Rule;
import com.ffaas.repository.FeatureFlagRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FlagService {

    private final FeatureFlagRepository repository;

    public FlagService(FeatureFlagRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public FlagResponse create(CreateFlagRequest request) {
        if (repository.existsByKey(request.key())) {
            throw new DuplicateFlagKeyException(request.key());
        }

        FeatureFlag flag = new FeatureFlag();
        flag.setKey(request.key());
        applyMutableFields(flag, request.name(), request.description(), request.enabled(),
                request.defaultState(), request.rules());

        try {
            return toResponse(repository.save(flag));
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateFlagKeyException(request.key());
        }
    }

    @Transactional(readOnly = true)
    public FlagResponse get(String key) {
        return toResponse(findOrThrow(key));
    }

    @Transactional(readOnly = true)
    public PagedResponse<FlagResponse> list(int page, int size) {
        Page<FeatureFlag> result = repository.findAll(PageRequest.of(page, size));
        List<FlagResponse> items = result.getContent().stream().map(this::toResponse).toList();
        return new PagedResponse<>(items, page, size, result.getTotalElements());
    }

    @Transactional
    public FlagResponse update(String key, UpdateFlagRequest request) {
        FeatureFlag flag = findOrThrow(key);
        applyMutableFields(flag, request.name(), request.description(), request.enabled(),
                request.defaultState(), request.rules());
        return toResponse(repository.save(flag));
    }

    @Transactional
    public void delete(String key) {
        FeatureFlag flag = findOrThrow(key);
        repository.delete(flag);
    }

    private FeatureFlag findOrThrow(String key) {
        return repository.findByKey(key)
                .orElseThrow(() -> new FlagNotFoundException(key));
    }

    private void applyMutableFields(
            FeatureFlag flag,
            String name,
            String description,
            boolean enabled,
            boolean defaultState,
            List<RuleDto> rules
    ) {
        flag.setName(name);
        flag.setDescription(description);
        flag.setEnabled(enabled);
        flag.setDefaultState(defaultState);
        flag.getRules().clear();
        if (rules != null) {
            for (RuleDto ruleDto : rules) {
                flag.addRule(toEntity(ruleDto));
            }
        }
    }

    private static Rule toEntity(RuleDto dto) {
        Rule rule = new Rule();
        rule.setPriority(dto.priority());
        rule.setServe(dto.serve());
        rule.setRolloutPercentage(dto.rolloutPercentage());
        rule.setConditions(dto.conditions().stream()
                .map(c -> new Condition(c.attribute(), c.operator(), c.value()))
                .toList());
        return rule;
    }

    private FlagResponse toResponse(FeatureFlag flag) {
        List<RuleResponse> rules = flag.getRules().stream()
                .map(FlagService::toRuleResponse)
                .toList();
        return new FlagResponse(
                flag.getKey(),
                flag.getName(),
                flag.getDescription(),
                flag.isEnabled(),
                flag.isDefaultState(),
                rules,
                flag.getCreatedAt(),
                flag.getUpdatedAt()
        );
    }

    private static RuleResponse toRuleResponse(Rule rule) {
        List<ConditionDto> conditions = rule.getConditions().stream()
                .map(c -> new ConditionDto(c.attribute(), c.operator(), c.value()))
                .toList();
        return new RuleResponse(
                rule.getId(),
                rule.getPriority(),
                rule.isServe(),
                rule.getRolloutPercentage(),
                conditions
        );
    }
}
