package com.vomattapi.infrastructure.security;

/**
 * Centralized security endpoint configuration.
 * <p>
 * All route patterns used in {@link WebSecurityConfig} are defined here.
 * Add new routes in the appropriate category instead of modifying filterChain directly.
 */
public final class SecurityEndpoints {

    private SecurityEndpoints() {
        // utility class
    }

    /** Auth endpoints that require no token */
    public static final String[] PUBLIC_AUTH = {
            "/api/v1/auth/signin",
            "/api/v1/auth/pre-signup",
            "/api/v1/auth/signup",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/refreshToken",
            "/api/v1/auth/generateVerificationCode"
    };

    /** Auth endpoints that require a valid token */
    public static final String[] AUTHENTICATED_AUTH = {
            "/api/v1/auth/signout",
            "/api/v1/auth/force-expire-token"
    };

    /** GET-only public endpoints */
    public static final String[] PUBLIC_GET = {
            "/api/v1/tags",
            "/api/v1/tags/**",
            "/api/v1/votes"
    };

    /**
     * Public user profile endpoint patterns (GET only).
     * Only /{username} is public; /me, /me/visibility, /search, /{userId} require authentication.
     */
    public static final String[] PUBLIC_GET_USER_PROFILE = {
            "/api/v1/users/{username}"
    };

    /** Swagger / OpenAPI UI */
    public static final String[] PUBLIC_SWAGGER = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml"
    };

    /** Actuator endpoints exposed without authentication */
    public static final String[] PUBLIC_ACTUATOR = {
            "/actuator/health"
    };

    /** Admin endpoints – authentication required (role check via @PreAuthorize) */
    public static final String[] AUTHENTICATED_ADMIN = {
            "/api/v1/admin/**"
    };
}
