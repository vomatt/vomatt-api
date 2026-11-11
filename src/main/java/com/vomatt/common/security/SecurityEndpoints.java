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
            "/api/auth/admin/verify-otp", "/api/auth/passkey/login/options", "/api/auth/passkey/login/verify",
            "/api/auth/admin/passkey/login/options", "/api/auth/admin/passkey/login/verify" };

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
    public static final String[] AUTHENTICATED_VOTES = { "/api/votes/my", "/api/votes/following", "/api/votes/bookmarked", "/api/votes/*/my-vote-status",
            "/api/votes/*/bookmark", "/api/votes/*/like", "/api/votes/*/voters", "/api/votes/*/invitees", "/api/votes/*/invitees/*",
            "/api/votes/*/report", "/api/votes/*/report.csv" };

    /**
     * Poll ID (UUID) path segment. Matching only UUIDs means named sub-paths like {@code /api/votes/my} are never permitted by the public rules,
     * without relying on {@link #AUTHENTICATED_VOTES} being ordered first.
     */
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final String VOTE = "/api/votes/{voteId:" + UUID + "}";

    /** An uploaded avatar, {@code <UUID>.jpg} only (see {@code UserService#AVATAR_URL_PREFIX}); also the controller mapping. */
    public static final String AVATAR_FILE =
            "/api/avatars/{file:" + UUID + "\\.jpg}";

    /** GET-only public endpoints (Poll details, an Invite-only Poll's owner, results after the Poll ends, comments and replies are readable without login) */
    public static final String[] PUBLIC_GET = { "/api/tags", "/api/tags/popular", "/api/votes", VOTE, VOTE + "/creator",
            VOTE + "/results", VOTE + "/comments", VOTE + "/comments/*/replies", "/api/users/{username}",
            "/api/users/{username}/votes", AVATAR_FILE };

    /** POST-only public endpoints: the about site's contact form (senders are anonymous) and the payment provider's webhook */
    public static final String[] PUBLIC_POST = { "/api/contact", "/api/billing/webhook" };

    /** Swagger / OpenAPI UI */
    public static final String[] PUBLIC_SWAGGER = { "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
            "/v3/api-docs.yaml" };

    /** Actuator endpoints exposed without authentication */
    public static final String[] PUBLIC_ACTUATOR = { "/actuator/health" };

    /** Admin endpoints – ADMIN role required (enforced at filter chain level) */
    public static final String[] AUTHENTICATED_ADMIN = { "/api/admin/**" };

    /**
     * Never reachable while acting as a community account ({@code X-Act-As}, see {@link ActAsFilter}): passkeys, sessions,
     * data export, phone binding and answering a community account invitation belong to a person, not to the group (a
     * community account is never phone-verified). {@link #PUBLIC_AUTH} (sign-in, refresh, logout) ignores the header.
     */
    public static final String[] ACT_AS_DENIED = { "/api/auth/passkey/register/**", "/api/users/me/passkeys", "/api/users/me/passkeys/**",
            "/api/users/me/sessions", "/api/users/me/sessions/**", "/api/users/me/export", "/api/users/me/phone/**",
            "/api/community-invitations/**" };

    /** DELETE-only routes never reachable while acting: account deletion. */
    public static final String[] ACT_AS_DENIED_DELETE = { "/api/users/*" };

    /** The community account's profile; writes need the OWNER or ADMIN role while acting. */
    public static final String[] ACT_AS_PROFILE = { "/api/users/me", "/api/users/me/avatar", "/api/users/me/visibility" };

    /**
     * Writes any role may send while acting, ANALYST included: member management (CommunityService authorizes by the
     * real member's role) and marking the community account's notifications read.
     */
    public static final String[] ACT_AS_ANY_ROLE = { "/api/communities/**", "/api/notifications/*/read", "/api/notifications/read-all" };

    /**
     * POST-only routes any role may send while acting: following, which UserService refuses for every community account
     * with 400 {@code user.follow.community}, so ANALYST gets that answer too instead of 403.
     */
    public static final String[] ACT_AS_ANY_ROLE_POST = { "/api/users/me/follows/*" };

    /**
     * Writes still allowed on a locked community account (see {@link ActAsFilter}), so its owners can sort out the
     * accounts their plan no longer covers: transferring ownership, unlocking, and choosing which accounts to keep.
     * Leaving (a member removing themself) is allowed too.
     */
    public static final String[] LOCKED_COMMUNITY_ALLOWED = { "/api/communities/*/owner", "/api/communities/*/unlock",
            "/api/users/me/communities/keep" };

    /** DELETE-only writes still allowed on a locked community account: deleting it. */
    public static final String[] LOCKED_COMMUNITY_ALLOWED_DELETE = { "/api/communities/*" };
}
