package com.vomattapi.application.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

import lombok.Data;

/**
 * API错误响应的标准格式
 */
@Data
public class ErrorResponse {

    private final int status;
    private final String message;
    private final Map<String, String> errors;
    private final String path;
    private final LocalDateTime timestamp = LocalDateTime.now();
    
    public ErrorResponse(int status, String message, Map<String, String> errors, String path) {
        this.status = status;
        this.message = message;
        this.errors = errors;
        this.path = path;
    }
}