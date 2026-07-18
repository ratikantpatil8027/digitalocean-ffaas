package com.ffaas.service;

public class FlagNotFoundException extends RuntimeException {

    private final String key;

    public FlagNotFoundException(String key) {
        super("Feature flag not found: " + key);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
