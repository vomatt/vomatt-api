package com.vomattapi.application.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.vomattapi.infrastructure.redis.RedisService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing JWT token blacklist
 * Blacklisted tokens are stored in Redis with TTL matching token expiration
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private final RedisService redisService;
    private static final String BLACKLIST_NAMESPACE = "jwt_blacklist";

    /**
     * Add a JWT token to the blacklist
     *
     * @param token JWT token to blacklist
     * @param expirationMs Token expiration time in milliseconds
     */
    public void blacklistToken(String token, long expirationMs) {
        long ttlSeconds = TimeUnit.MILLISECONDS.toSeconds(expirationMs);

        redisService.set(BLACKLIST_NAMESPACE, token, "blacklisted", Duration.ofSeconds(ttlSeconds));
        log.info("Token blacklisted: {} with TTL: {} seconds", token.substring(0, Math.min(10, token.length())), ttlSeconds);
    }

    /**
     * Check if a JWT token is blacklisted
     *
     * @param token JWT token to check
     * @return true if token is blacklisted, false otherwise
     */
    public boolean isTokenBlacklisted(String token) {
        boolean isBlacklisted = redisService.get(BLACKLIST_NAMESPACE, token, String.class) != null;

        if (isBlacklisted) {
            log.debug("Token is blacklisted: {}", token.substring(0, Math.min(10, token.length())));
        }

        return isBlacklisted;
    }

    /**
     * Remove a token from the blacklist (mainly for testing or admin purposes)
     *
     * @param token JWT token to remove from blacklist
     */
    public void removeFromBlacklist(String token) {
        redisService.delete(BLACKLIST_NAMESPACE, token);
        log.info("Token removed from blacklist: {}", token.substring(0, Math.min(10, token.length())));
    }
}
