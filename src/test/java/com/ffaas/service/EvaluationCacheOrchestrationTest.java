package com.ffaas.service;

import com.ffaas.api.dto.EvaluateRequest;
import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.api.dto.UpdateFlagRequest;
import com.ffaas.cache.EvaluationResultCache;
import com.ffaas.cache.FlagCache;
import com.ffaas.config.CacheProperties;
import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Operator;
import com.ffaas.domain.Rule;
import com.ffaas.engine.Reason;
import com.ffaas.engine.RuleEvaluator;
import com.ffaas.repository.FeatureFlagRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationCacheOrchestrationTest {

    @Mock
    private FeatureFlagRepository repository;

    @Mock
    private EntityManager entityManager;

    private FlagCache flagCache;
    private EvaluationResultCache evaluationResultCache;
    private EvaluationService evaluationService;
    private FlagService flagService;

    @BeforeEach
    void setUp() {
        CacheProperties properties = new CacheProperties(
                Duration.ofSeconds(60),
                10_000,
                Duration.ofSeconds(30),
                10_000
        );
        flagCache = new FlagCache(properties);
        evaluationResultCache = new EvaluationResultCache(properties);
        evaluationService = new EvaluationService(
                repository, new RuleEvaluator(), flagCache, evaluationResultCache);
        flagService = new FlagService(repository, entityManager, flagCache, evaluationResultCache);
    }

    @Test
    void shouldSkipRepositoryOnL2Hit() {
        UUID ruleId = UUID.randomUUID();
        EvaluateResponse cached = new EvaluateResponse("premium-dashboard", true, Reason.RULE_MATCH, ruleId);
        evaluationResultCache.put("premium-dashboard", "u1", Map.of("tier", "premium"), cached);

        EvaluateResponse response = evaluationService.evaluate(
                "premium-dashboard",
                new EvaluateRequest("u1", Map.of("tier", "premium"))
        );

        assertThat(response).isEqualTo(cached);
        verify(repository, never()).findByKey(any());
    }

    @Test
    void shouldSkipRepositoryOnL1HitAndPopulateL2() {
        UUID ruleId = UUID.fromString("0d5a7c1e-1111-2222-3333-444455556666");
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));
        flagCache.put("premium-dashboard", flag);

        EvaluateResponse response = evaluationService.evaluate(
                "premium-dashboard",
                new EvaluateRequest("u1", Map.of("tier", "premium"))
        );

        assertThat(response.enabled()).isTrue();
        assertThat(response.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(response.matchedRuleId()).isEqualTo(ruleId);
        verify(repository, never()).findByKey(any());
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("tier", "premium")))
                .contains(response);
    }

    @Test
    void shouldHitRepositoryOnceThenServeFromCaches() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));

        EvaluateRequest request = new EvaluateRequest("u1", Map.of("tier", "premium"));
        EvaluateResponse first = evaluationService.evaluate("premium-dashboard", request);
        EvaluateResponse second = evaluationService.evaluate("premium-dashboard", request);

        assertThat(first).isEqualTo(second);
        assertThat(first.enabled()).isTrue();
        verify(repository, times(1)).findByKey("premium-dashboard");
        assertThat(flagCache.get("premium-dashboard")).isPresent();
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("tier", "premium")))
                .contains(first);
    }

    @Test
    void shouldIsolateResultsBetweenDifferentContexts() {
        FeatureFlag flag = flag(true, false, List.of(
                rule(UUID.randomUUID(), 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));

        EvaluateResponse premium = evaluationService.evaluate(
                "premium-dashboard", new EvaluateRequest("u1", Map.of("tier", "premium")));
        EvaluateResponse free = evaluationService.evaluate(
                "premium-dashboard", new EvaluateRequest("u1", Map.of("tier", "free")));

        assertThat(premium.enabled()).isTrue();
        assertThat(free.enabled()).isFalse();
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("tier", "premium")))
                .contains(premium);
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("tier", "free")))
                .contains(free);
    }

    @Test
    void shouldHashNumericAndStringAttributeValuesDifferently() {
        FeatureFlag flag = flag(true, false, List.of(
                rule(UUID.randomUUID(), 0, true, List.of(new Condition("age", Operator.EQ, 34)))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));

        EvaluateResponse numeric = evaluationService.evaluate(
                "premium-dashboard", new EvaluateRequest("u1", Map.of("age", 34)));
        EvaluateResponse string = evaluationService.evaluate(
                "premium-dashboard", new EvaluateRequest("u1", Map.of("age", "34")));

        assertThat(numeric.enabled()).isTrue();
        assertThat(string.enabled()).isFalse();
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("age", 34)))
                .contains(numeric);
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("age", "34")))
                .contains(string);
    }

    @Test
    void shouldEvictBothLayersOnFlagUpdate() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));
        when(repository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        evaluationService.evaluate(
                "premium-dashboard", new EvaluateRequest("u1", Map.of("tier", "premium")));
        assertThat(flagCache.get("premium-dashboard")).isPresent();
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("tier", "premium")))
                .isPresent();

        flagService.update("premium-dashboard", new UpdateFlagRequest(
                null, "Premium Dashboard", null, true, false, List.of()));

        assertThat(flagCache.get("premium-dashboard")).isEmpty();
        assertThat(evaluationResultCache.get("premium-dashboard", "u1", Map.of("tier", "premium")))
                .isEmpty();
    }

    @Test
    void shouldReturn404AfterDeleteDespiteWarmCaches() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));

        evaluationService.evaluate(
                "premium-dashboard", new EvaluateRequest("u1", Map.of("tier", "premium")));
        assertThat(flagCache.get("premium-dashboard")).isPresent();

        flagService.delete("premium-dashboard");
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> evaluationService.evaluate(
                "premium-dashboard", new EvaluateRequest("u1", Map.of("tier", "premium"))))
                .isInstanceOf(FlagNotFoundException.class);
    }

    @Test
    void shouldServeStaleFromL1WhenDbDown() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));
        // Simulate L1 miss → DB failure → concurrent/stale L1 hit in the catch path.
        FlagCache spyFlagCache = org.mockito.Mockito.spy(flagCache);
        spyFlagCache.put("premium-dashboard", flag);
        FeatureFlag snapshot = spyFlagCache.get("premium-dashboard").orElseThrow();
        org.mockito.Mockito.doReturn(Optional.empty(), Optional.of(snapshot))
                .when(spyFlagCache).get("premium-dashboard");
        when(repository.findByKey("premium-dashboard"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        EvaluationService service = new EvaluationService(
                repository, new RuleEvaluator(), spyFlagCache, evaluationResultCache);

        EvaluateResponse response = service.evaluate(
                "premium-dashboard",
                new EvaluateRequest("u1", Map.of("tier", "premium"))
        );

        assertThat(response.enabled()).isTrue();
        assertThat(response.reason()).isEqualTo(Reason.RULE_MATCH);
        verify(repository, times(1)).findByKey("premium-dashboard");
    }

    @Test
    void shouldPropagate503WhenDbDownAndCacheCold() {
        when(repository.findByKey("premium-dashboard"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> evaluationService.evaluate(
                "premium-dashboard",
                new EvaluateRequest("u1", Map.of("tier", "premium"))
        ))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static FeatureFlag flag(boolean enabled, boolean defaultState, List<Rule> rules) {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("premium-dashboard");
        flag.setName("Premium Dashboard");
        flag.setEnabled(enabled);
        flag.setDefaultState(defaultState);
        for (Rule rule : rules) {
            flag.addRule(rule);
        }
        return flag;
    }

    private static Rule rule(UUID id, int priority, boolean serve, List<Condition> conditions) {
        Rule rule = new Rule();
        setId(rule, id);
        rule.setPriority(priority);
        rule.setServe(serve);
        rule.setConditions(conditions);
        return rule;
    }

    private static void setId(Rule rule, UUID id) {
        try {
            Field field = Rule.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(rule, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
