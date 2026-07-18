package com.ffaas.service;

public class DuplicateFlagKeyException extends RuntimeException {

    private final String key;

    public DuplicateFlagKeyException(String key) {
        super("Feature flag key already exists: " + key);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
