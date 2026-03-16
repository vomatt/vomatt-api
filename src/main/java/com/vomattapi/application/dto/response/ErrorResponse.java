package com.vomattapi.application.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

import lombok.Data;

/**
 * API error response standard format with success and errorCode fields
 *
 * @deprecated Use {@link ApiResponse} instead for consistent response format across all endpoints
 */
@Deprecated
@Data
public class ErrorResponse {

    private final boolean success = false;
    private final String errorCode;
    private final int status;
    private final String message;
    private final Map<String, String> errors;
    private final String path;
    private final LocalDateTime timestamp = LocalDateTime.now();

    public ErrorResponse(int status, String message, Map<String, String> errors, String path) {
        this.errorCode = "VALIDATION_ERROR";
        this.status = status;
        this.message = message;
        this.errors = errors;
        this.path = path;
    }

    public ErrorResponse(String errorCode, int status, String message, Map<String, String> errors, String path) {
        this.errorCode = errorCode;
        this.status = status;
        this.message = message;
        this.errors = errors;
        this.path = path;
    }
}
