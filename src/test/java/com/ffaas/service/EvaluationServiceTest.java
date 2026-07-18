package com.ffaas.service;

import com.ffaas.api.dto.EvaluateRequest;
import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Operator;
import com.ffaas.domain.Rule;
import com.ffaas.engine.Reason;
import com.ffaas.engine.RuleEvaluator;
import com.ffaas.repository.FeatureFlagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceTest {

    @Mock
    private FeatureFlagRepository repository;

    private EvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        evaluationService = new EvaluationService(repository, new RuleEvaluator());
    }

    @Test
    void shouldReturnRuleMatchWithMatchedRuleId() {
        UUID ruleId = UUID.fromString("0d5a7c1e-1111-2222-3333-444455556666");
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(
                        new Condition("subscriptionTier", Operator.EQ, "premium"),
                        new Condition("region", Operator.IN, List.of("us-east", "eu-west"))
                ))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));

        EvaluateResponse response = evaluationService.evaluate(
                "premium-dashboard",
                new EvaluateRequest("u1", Map.of("subscriptionTier", "premium", "region", "us-east"))
        );

        assertThat(response.flagKey()).isEqualTo("premium-dashboard");
        assertThat(response.enabled()).isTrue();
        assertThat(response.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(response.matchedRuleId()).isEqualTo(ruleId);
    }

    @Test
    void shouldReturnDefaultWhenNoRuleMatches() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("subscriptionTier", Operator.EQ, "premium")))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));

        EvaluateResponse response = evaluationService.evaluate(
                "premium-dashboard",
                new EvaluateRequest("u1", Map.of("subscriptionTier", "free"))
        );

        assertThat(response.enabled()).isFalse();
        assertThat(response.reason()).isEqualTo(Reason.DEFAULT);
        assertThat(response.matchedRuleId()).isNull();
    }

    @Test
    void shouldReturnFlagDisabledWhenToggleOff() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(false, true, List.of(
                rule(ruleId, 0, true, List.of(new Condition("subscriptionTier", Operator.EQ, "premium")))
        ));
        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(flag));

        EvaluateResponse response = evaluationService.evaluate(
                "premium-dashboard",
                new EvaluateRequest("u1", Map.of("subscriptionTier", "premium"))
        );

        assertThat(response.enabled()).isFalse();
        assertThat(response.reason()).isEqualTo(Reason.FLAG_DISABLED);
        assertThat(response.matchedRuleId()).isNull();
    }

    @Test
    void shouldReturn404ForUnknownFlag() {
        when(repository.findByKey("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> evaluationService.evaluate(
                "missing",
                new EvaluateRequest("u1", Map.of())
        ))
                .isInstanceOf(FlagNotFoundException.class)
                .hasMessageContaining("missing");
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
