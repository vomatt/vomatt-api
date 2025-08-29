package com.vomattapi.application.dto.response;

import lombok.Getter;

/**
 * Error codes for pre-signup operations to support i18n Frontend can use these codes to display localized error
 * messages
 */
@Getter
public enum PreSignupErrorCode {

    SUCCESS("SUCCESS", "Success"),

    // Username validation errors
    USERNAME_EMPTY("USERNAME_EMPTY", "Username cannot be empty"),
    USERNAME_TOO_SHORT("USERNAME_TOO_SHORT", "Username must be at least 3 characters long"),
    USERNAME_TOO_LONG("USERNAME_TOO_LONG", "Username must be at most 20 characters long"),
    USERNAME_INVALID_CHARS("USERNAME_INVALID_CHARS", "Username can only contain letters, numbers and underscores"),
    USERNAME_EXISTS("USERNAME_EXISTS", "Username is already taken"),

    // Email validation errors
    EMAIL_EMPTY("EMAIL_EMPTY", "Email cannot be empty"),
    EMAIL_INVALID_FORMAT("EMAIL_INVALID_FORMAT", "Email format is invalid"),
    EMAIL_EXISTS("EMAIL_EXISTS", "Email is already registered"),

    // Pre-signup process errors
    VERIFICATION_PENDING("VERIFICATION_PENDING", "Verification already in progress for this email"),
    VERIFICATION_SESSION_FAILED("VERIFICATION_SESSION_FAILED", "Failed to create verification session"),
    EMAIL_SEND_FAILED("EMAIL_SEND_FAILED", "Failed to send verification email"),
    VERIFICATION_CODE_INVALID("VERIFICATION_CODE_INVALID", "Invalid or expired verification code"),
    VERIFICATION_CODE_EXPIRED("VERIFICATION_CODE_EXPIRED", "Verification code has expired"),

    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error occurred");

    private final String code;
    private final String defaultMessage;

    PreSignupErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}