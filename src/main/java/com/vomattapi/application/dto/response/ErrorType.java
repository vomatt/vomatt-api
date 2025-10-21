package com.vomattapi.application.dto.response;

import lombok.Getter;

/**
 * Error codes for pre-signup operations to support i18n Frontend can use these codes to display localized error
 * messages
 */
@Getter
public enum ErrorType {

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

    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error occurred"),

    GENERATE_VERIFICATION_CODE_FAILED("GENERATE_VERIFICATION_CODE_FAILED", "Failed to generate verification code"),
    INVALID_VERIFICATION_CODE("INVALID_VERIFICATION_CODE", "Invalid verification code"),
    USER_NOT_FOUND("USER_NOT_FOUND", "User not found"),
    AUTHENTICATION_FAILED("AUTHENTICATION_FAILED", "Authentication failed"),

    // Validation and token errors
    VALIDATION_ERROR("VALIDATION_ERROR", "Request validation failed"),
    TOKEN_REFRESH_FAILED("TOKEN_REFRESH_FAILED", "Token refresh failed"),

    // General errors
    ENTITY_NOT_FOUND("ENTITY_NOT_FOUND", "The requested entity was not found"),
    BUSINESS_RULE_VIOLATION("BUSINESS_RULE_VIOLATION", "Business rule validation failed"),
    UNAUTHORIZED_OPERATION("UNAUTHORIZED_OPERATION", "You are not authorized to perform this operation"),
    ACCESS_DENIED("ACCESS_DENIED", "Access denied"),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "Invalid credentials provided"),

    // Vote-specific errors
    VOTE_NOT_FOUND("VOTE_NOT_FOUND", "Vote not found"),
    VOTE_OPTION_NOT_FOUND("VOTE_OPTION_NOT_FOUND", "Vote option not found"),
    VOTE_INSUFFICIENT_OPTIONS("VOTE_INSUFFICIENT_OPTIONS", "Minimum number of options required"),
    VOTE_TOO_MANY_OPTIONS("VOTE_TOO_MANY_OPTIONS", "Maximum number of options exceeded"),
    VOTE_INVALID_TIME("VOTE_INVALID_TIME", "Invalid vote time configuration"),
    VOTE_EXPIRED("VOTE_EXPIRED", "Vote has expired"),
    VOTE_NOT_STARTED("VOTE_NOT_STARTED", "Vote has not started yet"),
    VOTING_NOT_ALLOWED("VOTING_NOT_ALLOWED", "Voting is not allowed"),
    MULTIPLE_CHOICES_NOT_ALLOWED("MULTIPLE_CHOICES_NOT_ALLOWED", "Multiple choices not allowed for this vote"),
    VOTE_OPTION_MISMATCH("VOTE_OPTION_MISMATCH", "Vote option does not belong to this vote");


    private final String code;
    private final String defaultMessage;

    ErrorType(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
