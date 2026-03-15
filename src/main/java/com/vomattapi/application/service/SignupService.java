package com.vomattapi.application.service;

import com.vomattapi.application.dto.request.SignupRequest;
import com.vomattapi.application.dto.response.ErrorType;
import com.vomattapi.application.dto.response.MessageResponse;
import com.vomattapi.application.service.ValidationService.ValidationResult;
import com.vomattapi.domain.user.ERole;
import com.vomattapi.domain.user.Role;
import com.vomattapi.domain.user.User;
import com.vomattapi.domain.user.repository.RoleRepository;
import com.vomattapi.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 負責使用者註冊流程：
 * 1. 驗證預註冊驗證碼（Pre-signup OTP）
 * 2. 校驗欄位唯一性
 * 3. 建立 User 並以 BCrypt 加密驗證碼存入 DB
 * 4. 分配角色
 * 5. 清除 cache、發送歡迎信
 */
@Service
@RequiredArgsConstructor
public class SignupService {
    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final ValidationService validationService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmailService emailService;
    private final VerificationCodeService verificationCodeService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupResult processSignup(SignupRequest request) {
        log.info("Processing signup for username: {}, email: {}", request.getUsername(), request.getEmail());

        try {
            // Step 1: 驗證 pre-signup OTP
            SignupResult verificationResult = verifyPreSignupCode(request);
            if (!verificationResult.isSuccess()) {
                return verificationResult;
            }

            // Step 2: 校驗唯一性（username / email）
            ValidationResult validation = validateSignupData(request);
            if (!validation.isValid()) {
                log.warn("Signup validation failed: {}", validation.getErrorMessage());
                return SignupResult.failure(validation.getErrorType(), validation.getErrorMessage());
            }

            // Step 3: 建立 User（驗證碼 BCrypt 加密後存入）
            User user = createUserFromRequest(request);

            // Step 4: 指派角色
            assignRolesToUser(user, request.getRoles());

            // Step 5: 儲存
            User savedUser = userRepository.save(user);

            // Step 6: 清除 pre-signup cache
            clearPreSignupCache(request.getEmail(), request.getUsername());

            // Step 7: 發送歡迎信
            emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getUsername());

            log.info("Signup successful for user: {}", savedUser.getId());
            return SignupResult.success();

        } catch (Exception e) {
            log.error("Signup failed for username: {}", request.getUsername(), e);
            return SignupResult.failure(ErrorType.INTERNAL_ERROR, "Registration failed due to internal error");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public helpers
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isUsernameAvailable(String username) {
        return validationService.validateUsername(username).isValid();
    }

    public boolean isEmailAvailable(String email) {
        return validationService.validateEmail(email).isValid();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ValidationResult validateSignupData(SignupRequest request) {
        ValidationResult result = validationService.validatePreSignupData(request.getUsername(), request.getEmail());
        if (!result.isValid()) {
            return result;
        }
        return ValidationResult.valid();
    }

    /**
     * 建立 User entity，將驗證碼以 BCrypt 加密後存入 verification_code 欄位
     * （verification_code 在登入時作為 Spring Security password 使用）
     */
    private User createUserFromRequest(SignupRequest request) {
        String encodedVerificationCode = passwordEncoder.encode(request.getVerificationCode());
        return new User(
            request.getUsername(),
            request.getEmail(),
            request.getPhoneNumber(),
            encodedVerificationCode,
            request.getFirstName(),
            request.getLastName()
        );
    }

    private void assignRolesToUser(User user, Set<String> strRoles) {
        Set<Role> roles = new HashSet<>();

        if (strRoles == null || strRoles.isEmpty()) {
            roles.add(findRole(ERole.ROLE_USER));
        } else {
            strRoles.forEach(role -> {
                switch (role) {
                    case "admin" -> roles.add(findRole(ERole.ROLE_ADMIN));
                    case "mod"   -> roles.add(findRole(ERole.ROLE_MODERATOR));
                    default      -> roles.add(findRole(ERole.ROLE_USER));
                }
            });
        }

        user.setRoles(roles);
        log.debug("Assigned {} roles to user", roles.size());
    }

    private Role findRole(ERole roleEnum) {
        return roleRepository.findByName(roleEnum)
            .orElseThrow(() -> new RuntimeException("Role not found: " + roleEnum));
    }

    /**
     * 驗證 pre-signup OTP：
     * - 從 Redis cache 取出當初發送的驗證碼
     * - 比對使用者提交的驗證碼與 username
     */
    private SignupResult verifyPreSignupCode(SignupRequest request) {
        try {
            String key = request.getEmail() + ":" + request.getUsername();
            Map<String, Object> preSignupData = verificationCodeService.getVerificationData("pre_signup", key);

            if (preSignupData == null || preSignupData.isEmpty()) {
                log.warn("No pre-signup data found for email: {}", request.getEmail());
                return SignupResult.failure(
                    ErrorType.VERIFICATION_CODE_EXPIRED,
                    "Email verification required. Please complete pre-signup first."
                );
            }

            String cachedCode     = (String) preSignupData.get("verificationCode");
            String cachedUsername = (String) preSignupData.get("username");

            if (cachedCode == null) {
                log.error("No verification code in pre-signup data for email: {}", request.getEmail());
                return SignupResult.failure(
                    ErrorType.VERIFICATION_CODE_INVALID,
                    "Invalid verification data. Please restart the signup process."
                );
            }

            if (!cachedCode.equals(request.getVerificationCode())) {
                log.warn("Invalid verification code for email: {}", request.getEmail());
                return SignupResult.failure(ErrorType.INVALID_VERIFICATION_CODE, "Invalid verification code.");
            }

            if (!request.getUsername().equals(cachedUsername)) {
                log.warn("Username mismatch. Expected: {}, Provided: {}", cachedUsername, request.getUsername());
                return SignupResult.failure(
                    ErrorType.VALIDATION_ERROR,
                    "Username does not match the pre-registered username."
                );
            }

            log.info("Pre-signup verification successful for email: {}", request.getEmail());
            return SignupResult.success();

        } catch (Exception e) {
            log.error("Error verifying pre-signup code for email: {}", request.getEmail(), e);
            return SignupResult.failure(ErrorType.INTERNAL_ERROR, "Verification failed due to internal error");
        }
    }

    private void clearPreSignupCache(String email, String username) {
        try {
            verificationCodeService.deleteVerificationData("pre_signup", email + ":" + username);
            log.debug("Cleared pre-signup cache for email: {}, username: {}", email, username);
        } catch (Exception e) {
            // cache 清除失敗不影響主流程
            log.error("Failed to clear pre-signup cache for email: {}", email, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result wrapper
    // ─────────────────────────────────────────────────────────────────────────

    public static class SignupResult {
        private final boolean success;
        private final ErrorType errorType;
        private final String errorMessage;

        private SignupResult(boolean success, ErrorType errorType, String errorMessage) {
            this.success = success;
            this.errorType = errorType;
            this.errorMessage = errorMessage;
        }

        public static SignupResult success() {
            return new SignupResult(true, null, null);
        }

        public static SignupResult failure(ErrorType errorType, String errorMessage) {
            return new SignupResult(false, errorType, errorMessage);
        }

        public boolean isSuccess()       { return success; }
        public ErrorType getErrorType()  { return errorType; }
        public String getErrorMessage()  { return errorMessage; }

        public MessageResponse toMessageResponse() {
            return new MessageResponse(errorMessage);
        }
    }
}
