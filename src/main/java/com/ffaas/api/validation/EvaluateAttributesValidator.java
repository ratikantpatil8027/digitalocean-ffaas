package com.ffaas.api.validation;

import com.ffaas.api.dto.EvaluateRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;

public class EvaluateAttributesValidator implements ConstraintValidator<ValidEvaluateAttributes, EvaluateRequest> {

    private static final int MAX_ENTRIES = 50;

    @Override
    public boolean isValid(EvaluateRequest request, ConstraintValidatorContext context) {
        if (request == null || request.attributes() == null) {
            return true;
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        Map<String, Object> attributes = request.attributes();
        if (attributes.size() > MAX_ENTRIES) {
            valid = false;
            context.buildConstraintViolationWithTemplate("must contain at most " + MAX_ENTRIES + " entries")
                    .addPropertyNode("attributes")
                    .addConstraintViolation();
        }

        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (!isScalar(entry.getValue())) {
                valid = false;
                context.buildConstraintViolationWithTemplate(
                                "must be a scalar (string, number, or boolean)")
                        .addPropertyNode("attributes." + entry.getKey())
                        .addConstraintViolation();
            }
        }
        return valid;
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
