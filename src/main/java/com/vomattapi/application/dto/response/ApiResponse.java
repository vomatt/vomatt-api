package com.vomattapi.application.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String errorCode;
    private T data;
    private String message;
    private LocalDateTime timestamp;
    private String path;

    public ApiResponse() {
        this.timestamp = LocalDateTime.now();
    }

    public ApiResponse(boolean success, T data, String message) {
        this();
        this.success = success;
        this.data = data;
        this.message = message;
        this.errorCode = success ? ErrorType.SUCCESS.getCode() : null;
    }

    public ApiResponse(boolean success, String errorCode, T data, String message) {
        this();
        this.success = success;
        this.errorCode = errorCode;
        this.data = data;
        this.message = message;
    }

    // Success responses
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>(true, data, null);
        response.setErrorCode(ErrorType.SUCCESS.getCode());
        return response;
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        ApiResponse<T> response = new ApiResponse<>(true, data, message);
        response.setErrorCode(ErrorType.SUCCESS.getCode());
        return response;
    }

    public static <T> ApiResponse<T> success(String message) {
        ApiResponse<T> response = new ApiResponse<>(true, null, message);
        response.setErrorCode(ErrorType.SUCCESS.getCode());
        return response;
    }

    // Error responses with ErrorType enum
    public static <T> ApiResponse<T> error(ErrorType errorType) {
        return new ApiResponse<>(false, errorType.getCode(), null, errorType.getDefaultMessage());
    }

    public static <T> ApiResponse<T> error(ErrorType errorType, String customMessage) {
        return new ApiResponse<>(false, errorType.getCode(), null, customMessage);
    }

    public static <T> ApiResponse<T> error(ErrorType errorType, T data) {
        return new ApiResponse<>(false, errorType.getCode(), data, errorType.getDefaultMessage());
    }

    // Legacy error responses (for backward compatibility)
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, ErrorType.INTERNAL_ERROR.getCode(), null, message);
    }

    public static <T> ApiResponse<T> error(T data, String message) {
        return new ApiResponse<>(false, ErrorType.INTERNAL_ERROR.getCode(), data, message);
    }

    public ApiResponse<T> withPath(String path) {
        this.path = path;
        return this;
    }
}