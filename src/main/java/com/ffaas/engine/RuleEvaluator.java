package com.ffaas.engine;

import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Operator;
import com.ffaas.domain.Rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, framework-free flag evaluation. Ignores {@link Rule#getRolloutPercentage()} (bullet 07).
 */
public class RuleEvaluator {

    public EvaluationOutcome evaluate(FeatureFlag flag, String userId, Map<String, Object> attributes) {
        Map<String, Object> context = new HashMap<>();
        if (attributes != null) {
            context.putAll(attributes);
        }
        context.put("userId", userId);

        if (!flag.isEnabled()) {
            return new EvaluationOutcome(false, Reason.FLAG_DISABLED, null);
        }

        List<Rule> ordered = new ArrayList<>(flag.getRules());
        ordered.sort(Comparator.comparingInt(Rule::getPriority));

        for (Rule rule : ordered) {
            if (matchesAll(rule.getConditions(), context)) {
                return new EvaluationOutcome(rule.isServe(), Reason.RULE_MATCH, rule.getId());
            }
        }

        return new EvaluationOutcome(flag.isDefaultState(), Reason.DEFAULT, null);
    }

    private static boolean matchesAll(List<Condition> conditions, Map<String, Object> context) {
        if (conditions == null) {
            return false;
        }
        for (Condition condition : conditions) {
            if (!matches(condition, context)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Condition condition, Map<String, Object> context) {
        if (condition == null || condition.attribute() == null || condition.operator() == null) {
            return false;
        }
        if (!context.containsKey(condition.attribute())) {
            return false;
        }
        Object actual = context.get(condition.attribute());
        Object expected = condition.value();
        return switch (condition.operator()) {
            case EQ -> valuesEqual(actual, expected);
            case NEQ -> !valuesEqual(actual, expected);
            case IN -> isMember(actual, expected);
            case NOT_IN -> !isMember(actual, expected);
            case GT -> isGreater(actual, expected);
            case LT -> isLess(actual, expected);
        };
    }

    private static boolean isGreater(Object actual, Object expected) {
        Integer cmp = numericCompare(actual, expected);
        return cmp != null && cmp > 0;
    }

    private static boolean isLess(Object actual, Object expected) {
        Integer cmp = numericCompare(actual, expected);
        return cmp != null && cmp < 0;
    }

    private static boolean isMember(Object actual, Object expected) {
        if (!(expected instanceof Collection<?> collection)) {
            return false;
        }
        for (Object candidate : collection) {
            if (valuesEqual(actual, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        return a.equals(b);
    }

    /**
     * @return -1 / 0 / 1 for LT / EQ / GT when both sides are numbers; {@code null} if either is non-numeric.
     */
    private static Integer numericCompare(Object actual, Object expected) {
        if (!(actual instanceof Number) || !(expected instanceof Number)) {
            return null;
        }
        int cmp = Double.compare(((Number) actual).doubleValue(), ((Number) expected).doubleValue());
        return Integer.compare(cmp, 0);
    }
}
