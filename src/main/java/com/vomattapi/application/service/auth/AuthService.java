package com.vomattapi.application.service.auth;

import com.vomattapi.application.exception.BusinessRuleViolationException;
import com.vomattapi.application.exception.EntityNotFoundException;
import com.vomattapi.application.exception.InvalidVerificationCodeException;
import com.vomattapi.application.service.shared.EmailService;
import com.vomattapi.application.service.user.UserService;
import com.vomattapi.domain.user.User;
import com.vomattapi.infrastructure.constants.CacheConstants;
import com.vomattapi.infrastructure.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service responsible for authentication-related operations
 * Single responsibility: Handle login, verification code generation for existing users
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserService userService;
    private final VerificationCodeService verificationCodeService;
    private final RedisService redisService;
    private final EmailService emailService;

    /**
     * 產生並儲存驗證碼（僅限已存在的使用者）
     * @throws EntityNotFoundException 使用者不存在
     * @throws BusinessRuleViolationException 驗證碼更新失敗
     */
    public String generateVerificationCode(String email) {
        log.info("Generating verify code for email: {}", email);

        User user = userService.getUserByEmail(email);
        if (user == null) {
            throw new EntityNotFoundException("User", email);
        }

        String verificationCode = verificationCodeService.generateVerificationCode();

        redisService.set(CacheConstants.VERIFICATION_CODE, email, verificationCode, CacheConstants.VERIFICATION_CODE_TTL);

        boolean isChanged = userService.changeVerificationCode(email, verificationCode);
        if (!isChanged) {
            log.warn("Failed to update verification code for email: {}", email);
            throw new BusinessRuleViolationException("Failed to update verification code");
        }

        log.debug("Verification code generated and stored for email: {}", email);
        emailService.sendVerificationEmail(email, verificationCode);
        return verificationCode;
    }

    /**
     * 驗證 email 對應的驗證碼
     * @throws InvalidVerificationCodeException 驗證碼無效或已過期
     */
    public void verifyCode(String email, String providedCode) {
        String storedCode = redisService.get(CacheConstants.VERIFICATION_CODE, email, String.class);

        if (storedCode == null || !storedCode.equals(providedCode)) {
            log.warn("Invalid verification code provided for email: {}", email);
            throw new InvalidVerificationCodeException("Invalid or expired verification code for email: " + email);
        }

        redisService.delete(CacheConstants.VERIFICATION_CODE, email);
        log.info("Verification code verified successfully for email: {}", email);
    }
}