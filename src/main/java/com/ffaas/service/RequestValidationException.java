package com.ffaas.service;

import java.util.List;

/** Thrown when request semantic checks fail outside Bean Validation (e.g. key mismatch). */
public class RequestValidationException extends RuntimeException {

    private final List<FieldError> fieldErrors;

    public RequestValidationException(List<FieldError> fieldErrors) {
        super("Request validation failed");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public RequestValidationException(String field, String issue) {
        this(List.of(new FieldError(field, issue)));
    }

    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    public record FieldError(String field, String issue) {
    }
}
