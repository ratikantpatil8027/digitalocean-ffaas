package com.ffaas.service;

import com.ffaas.api.dto.EvaluateRequest;
import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.engine.EvaluationOutcome;
import com.ffaas.engine.RuleEvaluator;
import com.ffaas.repository.FeatureFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final FeatureFlagRepository repository;
    private final RuleEvaluator ruleEvaluator;

    public EvaluationService(FeatureFlagRepository repository, RuleEvaluator ruleEvaluator) {
        this.repository = repository;
        this.ruleEvaluator = ruleEvaluator;
    }

    @Transactional(readOnly = true)
    public EvaluateResponse evaluate(String key, EvaluateRequest request) {
        FeatureFlag flag = repository.findByKey(key)
                .orElseThrow(() -> new FlagNotFoundException(key));

        EvaluationOutcome outcome = ruleEvaluator.evaluate(flag, request.userId(), request.attributes());

        log.debug("evaluation flagKey={} userId={} reason={} matchedRuleId={}",
                key, request.userId(), outcome.reason(), outcome.matchedRuleId());

        return new EvaluateResponse(key, outcome.enabled(), outcome.reason(), outcome.matchedRuleId());
    }
}
