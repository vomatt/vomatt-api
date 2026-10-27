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
    public static final String[] PUBLIC_AUTH = { "/api/auth/send-otp", "/api/auth/verify-otp",
            "/api/auth/phone/send-otp", "/api/auth/phone/verify-otp", "/api/auth/google", "/api/auth/line",
            "/api/auth/apple", "/api/auth/refresh", "/api/auth/logout", "/api/auth/admin/send-otp",
            "/api/auth/admin/verify-otp", "/api/auth/passkey/login/options", "/api/auth/passkey/login/verify" };

    /**
     * Authenticated /api/users sub-paths; must be matched before {@link #PUBLIC_GET},
     * otherwise {@code /api/users/{username}} would also permit {@code /me} and {@code /search}.
     * A user's follower, following and mutual follower lists are for signed-in users only.
     */
    public static final String[] AUTHENTICATED_USERS = { "/api/users/me", "/api/users/me/**", "/api/users/search",
            "/api/users/search/**", "/api/users/*/followers", "/api/users/*/following", "/api/users/*/mutual-followers" };

    /**
     * Authenticated /api/votes sub-paths. The Poll paths in {@link #PUBLIC_GET} only match UUIDs, so they already don't permit these paths;
     * they are listed here explicitly as a second line of defense and matched before {@link #PUBLIC_GET}.
     */
    public static final String[] AUTHENTICATED_VOTES = { "/api/votes/my", "/api/votes/following", "/api/votes/*/my-vote-status",
            "/api/votes/*/voters", "/api/votes/*/invitees", "/api/votes/*/invitees/*" };

    /**
     * Poll ID (UUID) path segment. Matching only UUIDs means named sub-paths like {@code /api/votes/my} are never permitted by the public rules,
     * without relying on {@link #AUTHENTICATED_VOTES} being ordered first.
     */
    private static final String VOTE = "/api/votes/{voteId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}";

    /** GET-only public endpoints (Poll details, an Invite-only Poll's owner, results after the Poll ends, comments and replies are readable without login) */
    public static final String[] PUBLIC_GET = { "/api/tags", "/api/tags/**", "/api/votes", VOTE, VOTE + "/creator",
            VOTE + "/results", VOTE + "/comments", VOTE + "/comments/*/replies", "/api/users/{username}",
            "/api/users/{username}/votes" };

    /** Swagger / OpenAPI UI */
    public static final String[] PUBLIC_SWAGGER = { "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
            "/v3/api-docs.yaml" };

    /** Actuator endpoints exposed without authentication */
    public static final String[] PUBLIC_ACTUATOR = { "/actuator/health" };

    /** Admin endpoints – ADMIN role required (enforced at filter chain level) */
    public static final String[] AUTHENTICATED_ADMIN = { "/api/admin/**" };
}
