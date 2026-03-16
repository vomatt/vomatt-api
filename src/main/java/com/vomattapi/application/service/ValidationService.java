package com.vomattapi.application.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.vomattapi.application.dto.response.ErrorType;
import com.vomattapi.domain.user.repository.UserRepository;

/**
 * Service responsible for validation logic
 * Single responsibility: Validate data integrity and uniqueness
 */
@Service
@RequiredArgsConstructor
public class ValidationService {
    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
    private final UserRepository userRepository;

    /**
     * Validate if username is available for registration
     */
    public ValidationResult validateUsername(String username) {
        log.debug("Validating username availability: {}", username);

        if (username == null || username.trim().isEmpty()) {
            return ValidationResult.invalid(ErrorType.USERNAME_EMPTY);
        }

        if (username.length() < 3) {
            return ValidationResult.invalid(ErrorType.USERNAME_TOO_SHORT);
        }

        if (username.length() > 20) {
            return ValidationResult.invalid(ErrorType.USERNAME_TOO_LONG);
        }

        if (!username.matches("^[a-zA-Z0-9_]+$")) {
            return ValidationResult.invalid(ErrorType.USERNAME_INVALID_CHARS);
        }

        if (userRepository.existsByUsername(username)) {
            log.warn("Username validation failed: already exists: {}", username);
            return ValidationResult.invalid(ErrorType.USERNAME_EXISTS);
        }

        return ValidationResult.valid();
    }

    /**
     * Validate if email is available for registration
     */
    public ValidationResult validateEmail(String email) {
        log.debug("Validating email availability: {}", email);

        if (email == null || email.trim().isEmpty()) {
            return ValidationResult.invalid(ErrorType.EMAIL_EMPTY);
        }

        // Basic email format validation
        if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            return ValidationResult.invalid(ErrorType.EMAIL_INVALID_FORMAT);
        }

        if (userRepository.existsByEmail(email)) {
            log.warn("Email validation failed: already exists: {}", email);
            return ValidationResult.invalid(ErrorType.EMAIL_EXISTS);
        }

        return ValidationResult.valid();
    }

    /**
     * Validate both username and email for pre-signup
     */
    public ValidationResult validatePreSignupData(String username, String email) {
        log.debug("Validating pre-signup data for username: {}, email: {}", username, email);
        
        ValidationResult usernameResult = validateUsername(username);
        if (!usernameResult.isValid()) {
            return usernameResult;
        }
        
        ValidationResult emailResult = validateEmail(email);
        if (!emailResult.isValid()) {
            return emailResult;
        }
        
        return ValidationResult.valid();
    }

    /**
     * Validation result wrapper with error code support
     */
    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final ErrorType errorType;

        private ValidationResult(boolean valid, String errorMessage, ErrorType errorType) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.errorType = errorType;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, ErrorType.SUCCESS);
        }

        public static ValidationResult invalid(ErrorType errorType) {
            return new ValidationResult(false, errorType.getDefaultMessage(), errorType);
        }

        // Backward compatibility method
        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage, ErrorType.INTERNAL_ERROR);
        }
    }
}