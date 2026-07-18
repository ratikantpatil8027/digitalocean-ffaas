package com.ffaas.api;

import com.ffaas.api.dto.ErrorResponse;
import com.ffaas.api.dto.FieldIssue;
import com.ffaas.service.DuplicateFlagKeyException;
import com.ffaas.service.FlagNotFoundException;
import com.ffaas.service.RequestValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<FieldIssue> details = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            details.add(new FieldIssue(error.getField(), error.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors().forEach(error ->
                details.add(new FieldIssue(error.getObjectName(), error.getDefaultMessage())));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                details, request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        List<FieldIssue> details = ex.getConstraintViolations().stream()
                .map(v -> new FieldIssue(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                details, request.getRequestURI());
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ErrorResponse> handleRequestValidation(
            RequestValidationException ex,
            HttpServletRequest request
    ) {
        List<FieldIssue> details = ex.getFieldErrors().stream()
                .map(e -> new FieldIssue(e.field(), e.issue()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                details, request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Malformed JSON request",
                List.of(), request.getRequestURI());
    }

    @ExceptionHandler(FlagNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            FlagNotFoundException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, "FLAG_NOT_FOUND", ex.getMessage(),
                List.of(), request.getRequestURI());
    }

    @ExceptionHandler(DuplicateFlagKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(
            DuplicateFlagKeyException ex,
            HttpServletRequest request
    ) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_KEY", ex.getMessage(),
                List.of(), request.getRequestURI());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(
            DataAccessException ex,
            HttpServletRequest request
    ) {
        log.error("Database unavailable", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Service temporarily unavailable", List.of(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of(), request.getRequestURI());
    }

    private static ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String error,
            String message,
            List<FieldIssue> details,
            String path
    ) {
        ErrorResponse body = new ErrorResponse(
                status.value(),
                error,
                message,
                details,
                Instant.now(),
                path
        );
        return ResponseEntity.status(status).body(body);
    }
}
