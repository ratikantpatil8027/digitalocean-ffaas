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

class RuleEvaluatorTest {

    private RuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new RuleEvaluator();
    }

    @Test
    void shouldReturnFlagDisabledWhenGlobalToggleOffEvenIfRulesMatch() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(false, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("tier", "premium"));

        assertThat(outcome.enabled()).isFalse();
        assertThat(outcome.reason()).isEqualTo(Reason.FLAG_DISABLED);
        assertThat(outcome.matchedRuleId()).isNull();
    }

    @Test
    void shouldMatchEqWithNumericCoercionAcrossBoxing() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("age", Operator.EQ, 34)))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("age", 34.0));

        assertThat(outcome.enabled()).isTrue();
        assertThat(outcome.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(outcome.matchedRuleId()).isEqualTo(ruleId);
    }

    @Test
    void shouldNotMatchEqOnTypeMismatch() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, true, List.of(
                rule(ruleId, 0, false, List.of(new Condition("age", Operator.EQ, 34)))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("age", "34"));

        assertThat(outcome.enabled()).isTrue();
        assertThat(outcome.reason()).isEqualTo(Reason.DEFAULT);
        assertThat(outcome.matchedRuleId()).isNull();
    }

    @Test
    void shouldMatchInWhenValuePresentInList() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(
                        new Condition("region", Operator.IN, List.of("us-east", "eu-west"))))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("region", "eu-west"));

        assertThat(outcome.enabled()).isTrue();
        assertThat(outcome.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(outcome.matchedRuleId()).isEqualTo(ruleId);
    }

    @Test
    void shouldTreatMissingAttributeAsNonMatchForAllOperators() {
        for (Operator op : Operator.values()) {
            Object conditionValue = switch (op) {
                case EQ, NEQ, GT, LT -> 10;
                case IN, NOT_IN -> List.of("a", "b");
            };
            FeatureFlag flag = flag(true, true, List.of(
                    rule(UUID.randomUUID(), 0, false, List.of(
                            new Condition("missing", op, conditionValue)))
            ));

            EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of());

            assertThat(outcome.reason())
                    .as("missing attribute must not match for %s", op)
                    .isEqualTo(Reason.DEFAULT);
            assertThat(outcome.enabled()).isTrue();
            assertThat(outcome.matchedRuleId()).isNull();
        }
    }

    @Test
    void shouldReturnFalseForGtWhenContextValueNotNumeric() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("score", Operator.GT, 10)))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("score", "high"));

        assertThat(outcome.enabled()).isFalse();
        assertThat(outcome.reason()).isEqualTo(Reason.DEFAULT);
        assertThat(outcome.matchedRuleId()).isNull();
    }

    @Test
    void shouldFailRuleWhenAnySingleConditionFails() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(
                        new Condition("tier", Operator.EQ, "premium"),
                        new Condition("region", Operator.EQ, "us-east")))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1",
                Map.of("tier", "premium", "region", "eu-west"));

        assertThat(outcome.enabled()).isFalse();
        assertThat(outcome.reason()).isEqualTo(Reason.DEFAULT);
        assertThat(outcome.matchedRuleId()).isNull();
    }

    @Test
    void shouldPickLowestPriorityRuleWhenMultipleMatch() {
        UUID lowPriority = UUID.randomUUID();
        UUID highPriority = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(highPriority, 10, false, List.of(new Condition("tier", Operator.EQ, "premium"))),
                rule(lowPriority, 0, true, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("tier", "premium"));

        assertThat(outcome.enabled()).isTrue();
        assertThat(outcome.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(outcome.matchedRuleId()).isEqualTo(lowPriority);
    }

    @Test
    void shouldReturnDefaultStateWhenNoRuleMatches() {
        FeatureFlag flag = flag(true, true, List.of(
                rule(UUID.randomUUID(), 0, false, List.of(new Condition("tier", Operator.EQ, "premium")))
        ));

        EvaluationOutcome outcome = evaluator.evaluate(flag, "u1", Map.of("tier", "free"));

        assertThat(outcome.enabled()).isTrue();
        assertThat(outcome.reason()).isEqualTo(Reason.DEFAULT);
        assertThat(outcome.matchedRuleId()).isNull();
    }

    @Test
    void shouldReturnDefaultWithEmptyRules() {
        FeatureFlag offDefault = flag(true, false, List.of());
        FeatureFlag onDefault = flag(true, true, List.of());

        assertThat(evaluator.evaluate(offDefault, "u1", Map.of()).reason()).isEqualTo(Reason.DEFAULT);
        assertThat(evaluator.evaluate(offDefault, "u1", Map.of()).enabled()).isFalse();
        assertThat(evaluator.evaluate(offDefault, "u1", Map.of()).matchedRuleId()).isNull();

        assertThat(evaluator.evaluate(onDefault, "u1", Map.of()).reason()).isEqualTo(Reason.DEFAULT);
        assertThat(evaluator.evaluate(onDefault, "u1", Map.of()).enabled()).isTrue();
        assertThat(evaluator.evaluate(onDefault, "u1", Map.of()).matchedRuleId()).isNull();
    }

    @Test
    void shouldExposeUserIdAsContextAttribute() {
        UUID ruleId = UUID.randomUUID();
        FeatureFlag flag = flag(true, false, List.of(
                rule(ruleId, 0, true, List.of(new Condition("userId", Operator.EQ, "alice")))
        ));

        EvaluationOutcome fromParam = evaluator.evaluate(flag, "alice", Map.of());
        assertThat(fromParam.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(fromParam.matchedRuleId()).isEqualTo(ruleId);

        EvaluationOutcome paramWins = evaluator.evaluate(flag, "alice", Map.of("userId", "bob"));
        assertThat(paramWins.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(paramWins.matchedRuleId()).isEqualTo(ruleId);
    }

    private static FeatureFlag flag(boolean enabled, boolean defaultState, List<Rule> rules) {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey("test-flag");
        flag.setName("Test");
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
