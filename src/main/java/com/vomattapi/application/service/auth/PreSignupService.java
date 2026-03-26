package com.vomattapi.application.service.auth;

import com.vomattapi.application.service.shared.EmailService;
import com.vomattapi.application.dto.auth.PreSignupRequest;
import com.vomattapi.application.dto.common.BaseResponse;
import com.vomattapi.application.dto.common.ErrorType;
import com.vomattapi.application.dto.auth.PreSignupResponse;
import com.vomattapi.application.service.auth.ValidationService.ValidationResult;
import com.vomattapi.infrastructure.constants.CacheConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for pre-signup workflow orchestration Single responsibility: Coordinate pre-signup process
 * between validation, verification, and email services
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreSignupService {
    private final ValidationService validationService;
    private final VerificationCodeService verificationCodeService;
    private final EmailService emailService;

    /**
     * Handle complete pre-signup flow Orchestrates validation, verification code generation, storage, and email
     * sending
     */
    @Transactional
    public BaseResponse processPreSignup(PreSignupRequest request) {
        log.info("Processing pre-signup for email: {}, username: {}", request.getEmail(), request.getUsername());

        try {
            // Step 1: Check if verification already in progress
            if (isVerificationInProgress(request.getEmail(), request.getUsername())) {
                log.warn("Verification already in progress for email: {}", request.getEmail());
                return new BaseResponse(false, ErrorType.VERIFICATION_PENDING.getCode());
            }

            // Step 2: Validate username and email availability
            ValidationResult validation = validationService.validatePreSignupData(request.getUsername(),
                    request.getEmail());

            if (!validation.isValid()) {
                log.warn("Pre-signup validation failed: {}", validation.getErrorMessage());
                return new BaseResponse(false, validation.getErrorType().getCode());
            }

            // Step 3: Generate verification code
            String verificationCode = verificationCodeService.generateVerificationCode();

            // Step 4: Store pre-signup data using email and username as keys
            storePreSignupData(request.getEmail(), request.getUsername(), verificationCode);

            // Step 5: Send pre-signup verification email
            emailService.sendPreSignupEmail(request.getEmail(), verificationCode);

            log.info("Pre-signup successful for email: {}, username: {}", request.getEmail(), request.getUsername());

            return new BaseResponse(true);

        } catch (Exception e) {
            log.error("Pre-signup processing failed for email: {}", request.getEmail(), e);
            return new BaseResponse(false, ErrorType.INTERNAL_ERROR.getCode());
        }
    }

    /**
     * Verification pre-signup code and retrieve session data
     */
    public Map<String, Object> verifyPreSignupCode(String sessionKey, String verificationCode) {
        log.debug("Verifying pre-signup code for session: {}", sessionKey);

        var result = verificationCodeService.verificationCode(sessionKey, verificationCode);

        if (!result.isSuccess()) {
            log.warn("Pre-signup code verification failed for session: {}, reason: {}", sessionKey,
                    result.getErrorMessage());
            return null;
        }

        return result.getSessionData();
    }

    /**
     * Complete pre-signup by clearing session data
     */
    public void completePreSignup(String sessionKey) {
        log.debug("Completing pre-signup for session: {}", sessionKey);
        verificationCodeService.clearVerificationSession(sessionKey);
    }

    /**
     * Check if pre-signup session is still valid
     */
    public boolean isPreSignupSessionValid(String sessionKey) {
        return verificationCodeService.isSessionValid(sessionKey);
    }

    /**
     * Check if verification is already in progress for email
     */
    private boolean isVerificationInProgress(String email, String username) {
        try {
            String key = email + ":" + username;
            return verificationCodeService.hasVerificationData(CacheConstants.PRE_SIGNUP, key);
        } catch (Exception e) {
            log.error("Error checking verification status for email: {}", email, e);
            return false;
        }
    }

    /**
     * Store pre-signup data using email and username as cache keys
     */
    private void storePreSignupData(String email, String username, String verificationCode) {
        Map<String, Object> preSignupData = new HashMap<>();
        preSignupData.put("email", email);
        preSignupData.put("username", username);
        preSignupData.put("verificationCode", verificationCode);
        preSignupData.put("createdAt", System.currentTimeMillis());
        preSignupData.put("type", "PRE_SIGNUP");

        verificationCodeService.storeVerificationData(CacheConstants.PRE_SIGNUP, email + ":" + username, preSignupData,
                CacheConstants.PRE_SIGNUP_TTL);

        log.debug("Stored pre-signup data for email: {} and username: {}", email, username);
    }

    /**
     * Get pre-signup data by email
     */
    public Map<String, Object> getPreSignupDataByEmail(String email) {
        try {
            return verificationCodeService.getVerificationData(CacheConstants.PRE_SIGNUP_EMAIL, email);
        } catch (Exception e) {
            log.error("Error retrieving pre-signup data for email: {}", email, e);
            return null;
        }
    }

    /**
     * Resend verification code for pre-signup
     */
    public BaseResponse resendVerificationCode(String email) {
        log.info("Resending verification code for email: {}", email);

        try {
            // 以 email 為前綴搜尋 pre_signup:{email}:* 的資料
            Map<String, Map<String, Object>> preSignupMap = verificationCodeService.getAllCacheObjects(
                    CacheConstants.PRE_SIGNUP, email + ":*");
            if (preSignupMap == null || preSignupMap.isEmpty()) {
                log.warn("No pre-signup data found for email: {}", email);
                return new BaseResponse(false, ErrorType.VERIFICATION_CODE_EXPIRED.getCode());
            }

            Map<String, Object> preSignupData = preSignupMap.values().iterator().next();
            String username = (String) preSignupData.get("username");
            if (username == null) {
                log.error("Username not found in pre-signup data for email: {}", email);
                return new BaseResponse(false, ErrorType.INTERNAL_ERROR.getCode());
            }

            // Generate new verification code
            String newVerificationCode = verificationCodeService.generateVerificationCode();

            // Update cache with new verification code
            storePreSignupData(email, username, newVerificationCode);

            // Send new pre-signup verification email
            emailService.sendPreSignupEmail(email, newVerificationCode);

            log.info("Verification code resent successfully for email: {}", email);
            return new BaseResponse(true);

        } catch (Exception e) {
            log.error("Failed to resend verification code for email: {}", email, e);
            return new BaseResponse(false, ErrorType.EMAIL_SEND_FAILED.getCode());
        }
    }

    /**
     * Create session data from pre-signup request
     */
    private Map<String, Object> createSessionData(PreSignupRequest request, String verificationCode) {
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("username", request.getUsername());
        sessionData.put("email", request.getEmail());
        sessionData.put("verificationCode", verificationCode);
        sessionData.put("type", "PRE_SIGNUP");

        return sessionData;
    }

    /**
     * Get session data for a valid session
     */
    public Map<String, Object> getPreSignupSessionData(String sessionKey) {
        log.debug("Retrieving pre-signup session data for: {}", sessionKey);

        if (!isPreSignupSessionValid(sessionKey)) {
            log.warn("Attempted to retrieve data for invalid session: {}", sessionKey);
            return null;
        }

        // For now, we can use the verification service to get all data
        // In a more complex scenario, we might want different methods
        var result = verificationCodeService.verificationCode(sessionKey, "dummy");
        return result.isSuccess() ? result.getSessionData() : null;
    }
}