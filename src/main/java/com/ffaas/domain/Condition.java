package com.ffaas.domain;

public record Condition(String attribute, Operator operator, Object value) {
}
