package com.vomatt.common.constant;

public final class UserRole {

    private UserRole() {}

    public static final String USER      = "user";
    public static final String MODERATOR = "moderator";
    public static final String ADMIN     = "admin";
    /**
     * A community account (a group's page). Never assignable: only {@code POST /api/admin/communities} creates one, so it
     * stays out of {@link #PATTERN} and {@link #ALL}. Its holder cannot sign in or cast a Ballot.
     */
    public static final String COMMUNITY  = "community";

    /** Every assignable role; the one list for role validation. */
    public static final String PATTERN   = "user|moderator|admin";

    public static final java.util.List<String> ALL = java.util.List.of(USER, MODERATOR, ADMIN);

    /** Whether a role string maps to ROLE_ADMIN; same upper-casing as the authorities JwtAuthFilter builds. */
    public static boolean isAdmin(String role) {
        return role != null && role.toUpperCase(java.util.Locale.ROOT).equals("ADMIN");
    }
}
