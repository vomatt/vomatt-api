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

    /**
     * 需登入的 /api/votes 子路徑。{@link #PUBLIC_GET} 的 Poll 路徑只比對 UUID，本身已不會放行這些路徑；
     * 這裡明確列出作為第二道防線，排在 {@link #PUBLIC_GET} 之前比對。
     */
    public static final String[] AUTHENTICATED_VOTES = { "/api/votes/my", "/api/votes/*/my-vote-status",
            "/api/votes/*/voters" };

    /**
     * Poll ID（UUID）路徑段。只比對 UUID，{@code /api/votes/my} 等具名子路徑就不會被公開規則放行，
     * 不必依賴 {@link #AUTHENTICATED_VOTES} 排在前面。
     */
    private static final String VOTE = "/api/votes/{voteId:[0-9a-fA-F]+-[0-9a-fA-F-]+}";

    /** GET-only public endpoints（Poll 詳情、結束後的結果、留言與回覆未登入也可讀） */
    public static final String[] PUBLIC_GET = { "/api/tags", "/api/tags/**", "/api/votes", VOTE,
            VOTE + "/results", VOTE + "/comments", VOTE + "/comments/*/replies", "/api/users/{username}" };

    /** Swagger / OpenAPI UI */
    public static final String[] PUBLIC_SWAGGER = { "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
            "/v3/api-docs.yaml" };

    /** Actuator endpoints exposed without authentication */
    public static final String[] PUBLIC_ACTUATOR = { "/actuator/health" };

    /** Admin endpoints – ADMIN role required (enforced at filter chain level) */
    public static final String[] AUTHENTICATED_ADMIN = { "/api/admin/**" };
}
