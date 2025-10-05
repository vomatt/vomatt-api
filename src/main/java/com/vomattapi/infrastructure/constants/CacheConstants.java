package com.vomattapi.infrastructure.constants;

import java.time.Duration;

/**
 * Centralized cache configuration constants for Redis operations.
 * Provides consistent cache names and TTL values across the application.
 */
public final class CacheConstants {

    private CacheConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // Cache Names
    public static final String VERIFICATION_CODE = "verification_code";
    public static final String PRE_SIGNUP = "pre_signup";
    public static final String PRE_SIGNUP_EMAIL = "pre_signup_email";
    public static final String USER_CACHE = "user_cache";

    // TTL Durations
    public static final Duration VERIFICATION_CODE_TTL = Duration.ofMinutes(10);
    public static final Duration PRE_SIGNUP_TTL = Duration.ofMinutes(10);
    public static final Duration SESSION_COOKIE_TTL = Duration.ofHours(1);
}
