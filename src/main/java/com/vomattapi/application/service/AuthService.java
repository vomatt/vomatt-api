package com.vomattapi.application.service;

import com.vomattapi.infrastructure.redis.CacheKeyUtil;
import com.vomattapi.infrastructure.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

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
     * Generate and store verification code for existing user
     */
    public String generateVerificationCode(String email) {
        log.info("Generating verify code for email: {}", email);
        
        try {
            // Generate verification code
            String verificationCode = verificationCodeService.generateVerificationCode();
            
            // Store in Redis with 10 minute expiration
            redisService.set(CacheKeyUtil.buildKey("verify_code", email), verificationCode, Duration.ofMinutes(10));
            
            // Update member's verification code (if needed for existing flow)
            boolean isChanged = userService.changeVerificationCode(email, verificationCode);
            
            if (isChanged) {
                log.debug("Verification code generated and stored for email: {}", email);
                emailService.sendVerificationEmail(email, verificationCode);
                return verificationCode;
            } else {
                log.warn("Failed to update verification code for email: {}", email);
                return null;
            }
            
        } catch (Exception e) {
            log.error("Error generating verify code for email: {}", email, e);
            return null;
        }
    }
    
    /**
     * Verification the code for an email
     */
    public boolean verificationCode(String email, String providedCode) {
        try {
            String storedCode = redisService.get("verify_code", email, String.class);
            boolean isValid = storedCode != null && storedCode.equals(providedCode);
            
            if (isValid) {
                // Clear the used verification code
                redisService.delete("verify_code", email);
                log.info("Verification code verified successfully for email: {}", email);
            } else {
                log.warn("Invalid verification code provided for email: {}", email);
            }
            
            return isValid;
        } catch (Exception e) {
            log.error("Error verifying code for email: {}", email, e);
            return false;
        }
    }
}