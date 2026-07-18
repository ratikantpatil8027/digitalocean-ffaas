package com.ffaas.api.dto;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        int status,
        String error,
        String message,
        List<FieldIssue> details,
        Instant timestamp,
        String path
) {
}
