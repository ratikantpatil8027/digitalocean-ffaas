package com.ffaas.api.validation;

import com.ffaas.api.dto.ConditionDto;
import com.ffaas.api.dto.FlagRulesCarrier;
import com.ffaas.api.dto.RuleDto;
import com.ffaas.domain.Operator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FlagRulesValidator implements ConstraintValidator<ValidFlagRules, FlagRulesCarrier> {

    @Override
    public boolean isValid(FlagRulesCarrier value, ConstraintValidatorContext context) {
        if (value == null || value.rules() == null) {
            return true;
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        List<RuleDto> rules = value.rules();
        Set<Integer> priorities = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            RuleDto rule = rules.get(i);
            if (rule == null) {
                valid = false;
                context.buildConstraintViolationWithTemplate("must not be null")
                        .addPropertyNode("rules[" + i + "]")
                        .addConstraintViolation();
                continue;
            }
            if (!priorities.add(rule.priority())) {
                valid = false;
                context.buildConstraintViolationWithTemplate("Rule priorities must be unique within a flag")
                        .addPropertyNode("rules[" + i + "].priority")
                        .addConstraintViolation();
            }
            if (rule.conditions() == null) {
                continue;
            }
            for (int j = 0; j < rule.conditions().size(); j++) {
                ConditionDto condition = rule.conditions().get(j);
                if (condition == null) {
                    valid = false;
                    context.buildConstraintViolationWithTemplate("must not be null")
                            .addPropertyNode("rules[" + i + "].conditions[" + j + "]")
                            .addConstraintViolation();
                    continue;
                }
                if (condition.operator() == null) {
                    continue;
                }
                String issue = validateOperatorValue(condition.operator(), condition.value());
                if (issue != null) {
                    valid = false;
                    context.buildConstraintViolationWithTemplate(issue)
                            .addPropertyNode("rules[" + i + "].conditions[" + j + "].value")
                            .addConstraintViolation();
                }
            }
        }
        return valid;
    }

    private static String validateOperatorValue(Operator operator, Object value) {
        return switch (operator) {
            case EQ, NEQ -> isScalar(value) ? null : "EQ/NEQ operator requires a scalar value";
            case IN, NOT_IN -> {
                if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
                    yield "IN/NOT_IN operator requires a non-empty array";
                }
                for (Object element : collection) {
                    if (!isScalar(element)) {
                        yield "IN/NOT_IN operator requires a non-empty array of scalars";
                    }
                }
                yield null;
            }
            case GT, LT -> value instanceof Number ? null : "GT/LT operator requires a numeric value";
        };
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
