package com.vomattapi.application.service;

import com.vomattapi.infrastructure.redis.RedisService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service responsible for verification code generation and management Single responsibility: Handle verification codes
 * lifecycle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {
    private final RedisService redisService;

    private static final int CODE_LENGTH = 6;
    private static final int CODE_MIN_VALUE = 100000;
    private static final int CODE_MAX_VALUE = 999999;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Generate a secure verification code
     */
    public String generateVerificationCode() {
        int code = CODE_MIN_VALUE + SECURE_RANDOM.nextInt(CODE_MAX_VALUE - CODE_MIN_VALUE + 1);
        String generatedCode = String.valueOf(code);
        log.debug("Generated verification code with length: {}", generatedCode.length());
        return generatedCode;
    }

    /**
     * Create a verification session with code and expiration
     */
    public VerificationSession createVerificationSession(Duration expiration) {
        String sessionKey = UUID.randomUUID().toString();
        String verificationCode = generateVerificationCode();
        LocalDateTime expirationTime = LocalDateTime.now().plus(expiration);

        log.debug("Created verification session: {}, expires at: {}", sessionKey, expirationTime);

        return new VerificationSession(sessionKey, verificationCode, expirationTime, expiration);
    }

    /**
     * Store verification data with cache name and key
     */
    public void storeVerificationData(String cacheName, String key, Map<String, Object> data, Duration expiration) {
        try {
            data.put("createdAt", LocalDateTime.now().toString());
            data.put("expiresAt", LocalDateTime.now().plus(expiration).toString());

            redisService.hMultiSet(cacheName, key, data);
            redisService.expire(cacheName, key, expiration);

            log.debug("Stored verification data for cache: {} key: {}, expiration: {}", cacheName, key, expiration);
        } catch (Exception e) {
            log.error("Failed to store verification data for cache: {} key: {}", cacheName, key, e);
            throw new RuntimeException("Failed to store verification data", e);
        }
    }

    /**
     * Check if verification data exists for cache name and key
     */
    public boolean hasVerificationData(String cacheName, String key) {
        try {
            return redisService.hasKey(cacheName, key);
        } catch (Exception e) {
            log.error("Error checking verification data for cache: {} key: {}", cacheName, key, e);
            return false;
        }
    }

    /**
     * Get verification data by cache name and key
     */
    public Map<String, Object> getVerificationData(String cacheName, String key) {
        try {
            return redisService.hGetAll(cacheName, key, Object.class);
        } catch (Exception e) {
            log.error("Error retrieving verification data for cache: {} key: {}", cacheName, key, e);
            return null;
        }
    }

    /**
     * Delete verification data by cache name and key
     */
    public void deleteVerificationData(String cacheName, String key) {
        try {
            boolean deleted = redisService.delete(cacheName, key);
            log.debug("Deleted verification data for cache: {} key: {}, success: {}", cacheName, key, deleted);
        } catch (Exception e) {
            log.error("Error deleting verification data for cache: {} key: {}", cacheName, key, e);
            throw new RuntimeException("Failed to delete verification data", e);
        }
    }

    /**
     * Verification code and retrieve session data
     */
    public VerificationResult verificationCode(String sessionKey, String providedCode) {
        try {
            String storedCode = redisService.hGet("verification", sessionKey, "verificationCode", String.class);

            if (storedCode == null) {
                log.warn("Verification failed: session not found or expired: {}", sessionKey);
                return VerificationResult.failed("Session not found or expired");
            }

            if (!storedCode.equals(providedCode)) {
                log.warn("Verification failed: invalid code for session: {}", sessionKey);
                return VerificationResult.failed("Invalid verification code");
            }

            // Get all session data
            Map<String, Object> sessionData = redisService.hGetAll("verification", sessionKey, Object.class);

            if (sessionData.isEmpty()) {
                log.warn("Verification failed: no data found for session: {}", sessionKey);
                return VerificationResult.failed("Session data not found");
            }

            log.info("Verification successful for session: {}", sessionKey);
            return VerificationResult.success(sessionData);

        } catch (Exception e) {
            log.error("Error during verification for session: {}", sessionKey, e);
            return VerificationResult.failed("Verification error occurred");
        }
    }

    /**
     * Clear verification session
     */
    public void clearVerificationSession(String sessionKey) {
        try {
            redisService.delete("verification", sessionKey);
            log.debug("Cleared verification session: {}", sessionKey);
        } catch (Exception e) {
            log.error("Error clearing verification session: {}", sessionKey, e);
        }
    }

    /**
     * Check if session exists and is valid
     */
    public boolean isSessionValid(String sessionKey) {
        try {
            return redisService.hasKey("verification", sessionKey);
        } catch (Exception e) {
            log.error("Error checking session validity: {}", sessionKey, e);
            return false;
        }
    }

    /**
     * Check if username exists in pre_signup cache
     */
    public boolean isUsernameInPreSignupCache(String username) {
        try {
            Set<String> keys = redisService.keys("pre_signup", "*:" + username);
            boolean exists = !keys.isEmpty();
            log.debug("Username '{}' exists in pre_signup cache: {}", username, exists);
            return exists;
        } catch (Exception e) {
            log.error("Error checking username '{}' in pre_signup cache", username, e);
            return false;
        }
    }

    /**
     * Get all cache keys by cache name and pattern
     */
    public Set<String> getAllCacheKeys(String cacheName, String pattern) {
        try {
            Set<String> keys = redisService.keys(cacheName, pattern);
            log.debug("Found {} keys with cache '{}' pattern '{}'", keys.size(), cacheName, pattern);
            return keys;
        } catch (Exception e) {
            log.error("Error getting all keys with cache '{}' pattern '{}'", cacheName, pattern, e);
            return Set.of();
        }
    }

    /**
     * Get all cache objects by cache name and pattern
     */
    public Map<String, Map<String, Object>> getAllCacheObjects(String cacheName, String pattern) {
        try {
            Set<String> keys = redisService.keys(cacheName, pattern);
            Map<String, Map<String, Object>> cacheObjects = new HashMap<>();
            
            for (String key : keys) {
                // Extract the actual key part from the full cache key
                String actualKey = key.substring((cacheName + ":").length());
                Map<String, Object> data = redisService.hGetAll(cacheName, actualKey, Object.class);
                if (data != null && !data.isEmpty()) {
                    cacheObjects.put(key, data);
                }
            }
            
            log.debug("Found {} cache objects with cache '{}' pattern '{}'", cacheObjects.size(), cacheName, pattern);
            return cacheObjects;
        } catch (Exception e) {
            log.error("Error getting all cache objects with cache '{}' pattern '{}'", cacheName, pattern, e);
            return new HashMap<>();
        }
    }

    /**
     * Verification session data
     */
    @Getter
    public static class VerificationSession {
        private final String sessionKey;
        private final String verificationCode;
        private final LocalDateTime expirationTime;
        private final Duration duration;

        public VerificationSession(String sessionKey, String verificationCode, LocalDateTime expirationTime,
                Duration duration) {
            this.sessionKey = sessionKey;
            this.verificationCode = verificationCode;
            this.expirationTime = expirationTime;
            this.duration = duration;
        }

        public long getExpirationMinutes() {
            return duration.toMinutes();
        }
    }

    /**
     * Verification result wrapper
     */
    @Getter
    public static class VerificationResult {
        private final boolean success;
        private final String errorMessage;
        private final Map<String, Object> sessionData;

        private VerificationResult(boolean success, String errorMessage, Map<String, Object> sessionData) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.sessionData = sessionData != null ? sessionData : new HashMap<>();
        }

        public static VerificationResult success(Map<String, Object> sessionData) {
            return new VerificationResult(true, null, sessionData);
        }

        public static VerificationResult failed(String errorMessage) {
            return new VerificationResult(false, errorMessage, null);
        }
    }
}