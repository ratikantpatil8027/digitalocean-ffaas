package com.ffaas.engine;

import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Operator;
import com.ffaas.domain.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PercentageRolloutTest {

    private RuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RuleEvaluator();
    }

    @Test
    void shouldAlwaysExcludeAtZeroPercent() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag("rollout-flag", true, true, List.of(
                rule(ruleId, 0, true, 0, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        for (int i = 0; i < 200; i++) {
            EvaluationOutcome outcome = evaluator.evaluate(flag, "user-" + i, Map.of("tier", "premium"));
            assertThat(outcome.enabled()).isTrue();
            assertThat(outcome.reason()).isEqualTo(Reason.ROLLOUT_EXCLUDED);
            assertThat(outcome.matchedRuleId()).isEqualTo(ruleId);
        }
    }

    @Test
    void shouldAlwaysIncludeAtHundredPercent() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag("rollout-flag", true, false, List.of(
                rule(ruleId, 0, true, 100, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        for (int i = 0; i < 200; i++) {
            EvaluationOutcome outcome = evaluator.evaluate(flag, "user-" + i, Map.of("tier", "premium"));
            assertThat(outcome.enabled()).isTrue();
            assertThat(outcome.reason()).isEqualTo(Reason.RULE_MATCH);
            assertThat(outcome.matchedRuleId()).isEqualTo(ruleId);
        }
    }

    @Test
    void shouldBehaveIdenticallyToCoreWhenPercentageNull() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag("rollout-flag", true, false, List.of(
                rule(ruleId, 0, true, null, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        EvaluationOutcome match = evaluator.evaluate(flag, "u1", Map.of("tier", "premium"));
        assertThat(match.enabled()).isTrue();
        assertThat(match.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(match.matchedRuleId()).isEqualTo(ruleId);

        EvaluationOutcome miss = evaluator.evaluate(flag, "u1", Map.of("tier", "free"));
        assertThat(miss.enabled()).isFalse();
        assertThat(miss.reason()).isEqualTo(Reason.DEFAULT);
        assertThat(miss.matchedRuleId()).isNull();
    }

    @Test
    void shouldIncludeRoughly30PercentOf10000Users() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag("rollout-flag", true, false, List.of(
                rule(ruleId, 0, true, 30, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        int included = 0;
        for (int i = 0; i < 10_000; i++) {
            EvaluationOutcome outcome = evaluator.evaluate(flag, "user-" + i, Map.of("tier", "premium"));
            if (outcome.reason() == Reason.RULE_MATCH) {
                included++;
            } else {
                assertThat(outcome.reason()).isEqualTo(Reason.ROLLOUT_EXCLUDED);
            }
        }

        assertThat(included).isBetween(2700, 3300);
    }

    @Test
    void shouldNotFallThroughToLowerPriorityRuleWhenExcluded() {
        UUID priorityZero = UUID.randomUUID();
        UUID priorityOne = UUID.randomUUID();
        FeatureFlag flag = flag("rollout-flag", true, false, List.of(
                rule(priorityZero, 0, true, 0, List.of(new Condition("tier", Operator.EQ, "premium"))),
                rule(priorityOne, 1, true, null, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("tier", "premium"));

        assertThat(outcome.enabled()).isFalse();
        assertThat(outcome.reason()).isEqualTo(Reason.ROLLOUT_EXCLUDED);
        assertThat(outcome.matchedRuleId()).isEqualTo(priorityZero);
    }

    private static FeatureFlag flag(String key, boolean enabled, boolean defaultState, List<Rule> rules) {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey(key);
        flag.setName("Test");
        flag.setEnabled(enabled);
        flag.setDefaultState(defaultState);
        for (Rule rule : rules) {
            flag.addRule(rule);
        }
        return flag;
    }

    private static Rule rule(
            UUID id, int priority, boolean serve, Integer rolloutPercentage, List<Condition> conditions
    ) {
        Rule rule = new Rule();
        setId(rule, id);
        rule.setPriority(priority);
        rule.setServe(serve);
        rule.setRolloutPercentage(rolloutPercentage);
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
