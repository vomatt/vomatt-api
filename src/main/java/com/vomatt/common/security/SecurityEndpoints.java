package com.vomatt.common.security;

/**
 * Centralized security endpoint configuration.
 * <p>
 * All route patterns used in {@link com.vomatt.common.config.SecurityConfig} are defined here. Add new routes in
 * the appropriate category instead of modifying filterChain directly.
 */
public final class SecurityEndpoints {

    private SecurityEndpoints() {
        // utility class
    }

    /** Auth endpoints that require no token. */
    public static final String[] PUBLIC_AUTH = { "/api/auth/send-otp", "/api/auth/verify-otp", "/api/auth/check-email",
            "/api/auth/phone/send-otp", "/api/auth/phone/verify-otp", "/api/auth/google", "/api/auth/line",
            "/api/auth/apple", "/api/auth/refresh", "/api/auth/logout" };

    /**
     * 需登入的 /api/users 子路徑；必須排在 {@link #PUBLIC_GET} 之前比對，
     * 否則 {@code /api/users/{username}} 會把 {@code /me}、{@code /search} 一併放行。
     */
    public static final String[] AUTHENTICATED_USERS = { "/api/users/me", "/api/users/me/**", "/api/users/search" };

    /** GET-only public endpoints */
    public static final String[] PUBLIC_GET = { "/api/tags", "/api/tags/**", "/api/votes", "/api/users/{username}" };

    /** Swagger / OpenAPI UI */
    public static final String[] PUBLIC_SWAGGER = { "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
            "/v3/api-docs.yaml" };

    /** Actuator endpoints exposed without authentication */
    public static final String[] PUBLIC_ACTUATOR = { "/actuator/health" };

    /** Admin endpoints – ADMIN role required (enforced at filter chain level) */
    public static final String[] AUTHENTICATED_ADMIN = { "/api/admin/**" };
}
