package com.vomatt.common.constant;

import java.util.Set;

/**
 * Usernames that cannot be taken because web profiles live at /{username} and would shadow web routes
 * (or API sub-paths such as /api/users/search). Existing accounts holding one of them are not renamed.
 */
public final class ReservedUsernames {

    private ReservedUsernames() {}

    // Regex alternation so it can be embedded in compile-time @Pattern constants
    private static final String ALTERNATION =
            "search|account|explore|login|notifications|poll|sanity|api|me|my|admin|settings";

    /** Username rule for profile updates: 3-50 characters of [a-z0-9_], not a reserved name. */
    public static final String USERNAME_PATTERN = "^(?!(?:" + ALTERNATION + ")$)[a-z0-9_]{3,50}$";

    private static final Set<String> NAMES = Set.of(ALTERNATION.split("\\|"));

    public static boolean contains(String username) {
        return NAMES.contains(username);
    }
}
