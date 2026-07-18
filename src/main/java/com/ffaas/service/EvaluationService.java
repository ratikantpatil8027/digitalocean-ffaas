package com.ffaas.service;

import com.ffaas.api.dto.EvaluateRequest;
import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.cache.EvaluationResultCache;
import com.ffaas.cache.FlagCache;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.engine.EvaluationOutcome;
import com.ffaas.engine.RuleEvaluator;
import com.ffaas.repository.FeatureFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final FeatureFlagRepository repository;
    private final RuleEvaluator ruleEvaluator;
    private final FlagCache flagCache;
    private final EvaluationResultCache evaluationResultCache;

    public EvaluationService(
            FeatureFlagRepository repository,
            RuleEvaluator ruleEvaluator,
            FlagCache flagCache,
            EvaluationResultCache evaluationResultCache
    ) {
        this.repository = repository;
        this.ruleEvaluator = ruleEvaluator;
        this.flagCache = flagCache;
        this.evaluationResultCache = evaluationResultCache;
    }

    public EvaluateResponse evaluate(String key, EvaluateRequest request) {
        var attributes = request.attributes();

        Optional<EvaluateResponse> cachedResult =
                evaluationResultCache.get(key, request.userId(), attributes);
        if (cachedResult.isPresent()) {
            return cachedResult.get();
        }

        FeatureFlag flag = loadFlag(key);
        EvaluationOutcome outcome = ruleEvaluator.evaluate(flag, request.userId(), attributes);

        log.debug("evaluation flagKey={} userId={} reason={} matchedRuleId={}",
                key, request.userId(), outcome.reason(), outcome.matchedRuleId());

        EvaluateResponse response = new EvaluateResponse(
                key, outcome.enabled(), outcome.reason(), outcome.matchedRuleId());
        evaluationResultCache.put(key, request.userId(), attributes, response);
        return response;
    }

    /**
     * L1 → repository. On {@link DataAccessException}, serve from L1 if present (race / still-live entry).
     * Expired L1 entries are not recoverable — acceptable per ARCHITECTURE §4.
     */
    private FeatureFlag loadFlag(String key) {
        Optional<FeatureFlag> cached = flagCache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            FeatureFlag flag = repository.findByKey(key)
                    .orElseThrow(() -> new FlagNotFoundException(key));
            flagCache.put(key, flag);
            return flagCache.get(key).orElse(flag);
        } catch (DataAccessException ex) {
            // Expired entries are not recoverable from Caffeine; only still-live (e.g. concurrent put) help.
            Optional<FeatureFlag> stale = flagCache.get(key);
            if (stale.isPresent()) {
                log.warn("db_unavailable_served_from_cache flagKey={}", key);
                return stale.get();
            }
            throw ex;
        }
    }
}
