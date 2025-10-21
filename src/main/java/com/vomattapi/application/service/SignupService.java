package com.vomattapi.application.service;

import com.vomattapi.application.dto.request.SignupRequest;
import com.vomattapi.application.dto.response.ErrorCode;
import com.vomattapi.application.dto.response.MessageResponse;
import com.vomattapi.application.service.ValidationService.ValidationResult;
import com.vomattapi.domain.user.ERole;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.Role;
import com.vomattapi.domain.user.repository.UserRepository;
import com.vomattapi.domain.user.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Service responsible for user registration workflow Single responsibility: Handle complete signup process
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupService {
    private final ValidationService validationService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final VerificationCodeService verificationCodeService;

    /**
     * Process complete signup workflow
     */
    @Transactional
    public SignupResult processSignup(SignupRequest signupRequest) {
        log.info("Processing signup for username: {}, email: {}", signupRequest.getUsername(),
                signupRequest.getEmail());

        try {
            // Step 1: Verification pre-signup verification code
            SignupResult verificationResult = verifyPreSignupCode(signupRequest);
            if (!verificationResult.isSuccess()) {
                return verificationResult;
            }

            // Step 2: Validate data
            ValidationResult validation = validateSignupData(signupRequest);
            if (!validation.isValid()) {
                log.warn("Signup validation failed: {}", validation.getErrorMessage());
                return SignupResult.failure(validation.getErrorCode(), validation.getErrorMessage());
            }

            // Step 3: Create member
            User user = createUserFromRequest(signupRequest);

            // Step 4: Assign roles
            assignRolesToUser(user, signupRequest.getRoles());

            // Step 5: Save member
            User savedUser = userRepository.save(user);

            // Step 6: Clear pre-signup cache
            clearPreSignupCache(signupRequest.getEmail(), signupRequest.getUsername());

            // Step 7: Send welcome email
            emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getUsername());

            log.info("Signup successful for member: {}", savedUser.getId());
            return SignupResult.success();

        } catch (Exception e) {
            log.error("Signup failed for username: {}", signupRequest.getUsername(), e);
            return SignupResult.failure(ErrorCode.INTERNAL_ERROR, "Registration failed due to internal error");
        }
    }

    /**
     * Validate signup data using ValidationService
     */
    private ValidationResult validateSignupData(SignupRequest signupRequest) {
        // Validate username and email
        ValidationResult basicValidation = validationService.validatePreSignupData(signupRequest.getUsername(),
                signupRequest.getEmail());

        if (!basicValidation.isValid()) {
            return basicValidation;
        }

        return ValidationResult.valid();
    }

    /**
     * Create member entity from signup request
     */
    private User createUserFromRequest(SignupRequest signupRequest) {
        User user = new User(signupRequest.getUsername(), signupRequest.getEmail(),
                signupRequest.getPhoneNumber(), passwordEncoder.encode(signupRequest.getVerificationCode()), signupRequest.getFirstName(), signupRequest.getLastName());

        log.debug("Created member entity for username: {}", signupRequest.getUsername());
        return user;
    }

    /**
     * Assign roles to member based on request
     */
    private void assignRolesToUser(User user, Set<String> strRoles) {
        Set<Role> roles = new HashSet<>();

        if (strRoles == null || strRoles.isEmpty()) {
            // Default role
            Role userRole = roleRepository.findByName(ERole.ROLE_USER)
                    .orElseThrow(() -> new RuntimeException("Error: User Role is not found."));
            roles.add(userRole);
        } else {
            strRoles.forEach(role -> {
                switch (role) {
                case "admin":
                    Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN)
                            .orElseThrow(() -> new RuntimeException("Error: Admin Role is not found."));
                    roles.add(adminRole);
                    break;
                case "mod":
                    Role modRole = roleRepository.findByName(ERole.ROLE_MODERATOR)
                            .orElseThrow(() -> new RuntimeException("Error: Moderator Role is not found."));
                    roles.add(modRole);
                    break;
                default:
                    Role userRole = roleRepository.findByName(ERole.ROLE_USER)
                            .orElseThrow(() -> new RuntimeException("Error: User Role is not found."));
                    roles.add(userRole);
                }
            });
        }

        user.setRoles(roles);
        log.debug("Assigned {} roles to member", roles.size());
    }

    /**
     * Check if username is available
     */
    public boolean isUsernameAvailable(String username) {
        ValidationResult result = validationService.validateUsername(username);
        return result.isValid();
    }

    /**
     * Check if email is available
     */
    public boolean isEmailAvailable(String email) {
        ValidationResult result = validationService.validateEmail(email);
        return result.isValid();
    }

    /**
     * Verification pre-signup verification code
     */
    private SignupResult verifyPreSignupCode(SignupRequest signupRequest) {
        try {
            String key = signupRequest.getEmail() + ":" + signupRequest.getUsername();
            // Get pre-signup data from cache
            Map<String, Object> preSignupData = verificationCodeService.getVerificationData("pre_signup", key);
            
            if (preSignupData == null || preSignupData.isEmpty()) {
                log.warn("No pre-signup data found for email: {}", signupRequest.getEmail());
                return SignupResult.failure(ErrorCode.VERIFICATION_CODE_EXPIRED, "Email verification required. Please complete pre-signup first.");
            }

            String cachedVerificationCode = (String) preSignupData.get("verificationCode");
            String cachedUsername = (String) preSignupData.get("username");

            if (cachedVerificationCode == null) {
                log.error("No verification code found in pre-signup data for email: {}", signupRequest.getEmail());
                return SignupResult.failure(ErrorCode.VERIFICATION_CODE_INVALID, "Invalid verification data. Please restart the signup process.");
            }

            if (!cachedVerificationCode.equals(signupRequest.getVerificationCode())) {
                log.warn("Invalid verification code provided for email: {}", signupRequest.getEmail());
                return SignupResult.failure(ErrorCode.INVALID_VERIFICATION_CODE, "Invalid verification code.");
            }

            if (!signupRequest.getUsername().equals(cachedUsername)) {
                log.warn("Username mismatch. Expected: {}, Provided: {}", cachedUsername, signupRequest.getUsername());
                return SignupResult.failure(ErrorCode.VALIDATION_ERROR, "Username does not match the pre-registered username.");
            }

            log.info("Pre-signup verification successful for email: {}", signupRequest.getEmail());
            return SignupResult.success();
            
        } catch (Exception e) {
            log.error("Error verifying pre-signup code for email: {}", signupRequest.getEmail(), e);
            return SignupResult.failure(ErrorCode.INTERNAL_ERROR, "Verification failed due to internal error");
        }
    }

    /**
     * Clear pre-signup cache data
     */
    private void clearPreSignupCache(String email, String username) {
        try {
            String key = email + ":" + username;
            // Use Redis service to delete the cache keys directly
            verificationCodeService.deleteVerificationData("pre_signup", key);
            
            log.debug("Cleared pre-signup cache for email: {} and username: {}", email, username);
        } catch (Exception e) {
            log.error("Failed to clear pre-signup cache for email: {} and username: {}", email, username, e);
            // Don't fail the signup if cache clearing fails
        }
    }

    /**
     * Signup result wrapper
     */
    public static class SignupResult {
        private final boolean success;
        private final ErrorCode errorCode;
        private final String errorMessage;

        private SignupResult(boolean success, ErrorCode errorCode, String errorMessage) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public static SignupResult success() {
            return new SignupResult(true, null, null);
        }

        public static SignupResult failure(ErrorCode errorCode, String errorMessage) {
            return new SignupResult(false, errorCode, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public ErrorCode getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public MessageResponse toMessageResponse() {
            return new MessageResponse(errorMessage);
        }
    }
}