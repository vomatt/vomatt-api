package com.vomattapi.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pre-signup response DTO with error code support for i18n
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreSignupResponse {
    private boolean success;
    private String message;
    private String errorType; // Error type for frontend i18n
    private String sessionKey; // For tracking the pre-signup session
    private long expirationMinutes; // How long the verification code is valid

    // Success response constructor
    public static PreSignupResponse success(String sessionKey, long expirationMinutes) {
        return new PreSignupResponse(true, ErrorType.SUCCESS.getDefaultMessage(),
                                   ErrorType.SUCCESS.getCode(), sessionKey, expirationMinutes);
    }

    // Error response constructor
    public static PreSignupResponse error(ErrorType errorType) {
        return new PreSignupResponse(false, errorType.getDefaultMessage(),
                                   errorType.getCode(), null, 0);
    }

    // Backward compatibility constructor
    public PreSignupResponse(boolean success, String message, String sessionKey, long expirationMinutes) {
        this.success = success;
        this.message = message;
        this.errorType = success ? ErrorType.SUCCESS.getCode() : ErrorType.INTERNAL_ERROR.getCode();
        this.sessionKey = sessionKey;
        this.expirationMinutes = expirationMinutes;
    }
}