package com.vomatt.repository;

/** SQL predicates on a user column, shared by the Poll lists and the notification feed. */
final class UserFilters {

    private UserFilters() {
    }

    /** The user in {@code userColumn} is not suspended. */
    static String active(String userColumn) {
        return "EXISTS (SELECT 1 FROM vomatt.users a WHERE a.id = %s AND a.active)".formatted(userColumn);
    }

    /** The user in {@code userColumn} and the viewer (the {@code viewerParam} placeholder) have not blocked each other. */
    static String notBlockedEitherWay(String userColumn, String viewerParam) {
        return ("NOT EXISTS (SELECT 1 FROM vomatt.user_blocks x WHERE x.blocker_id = %2$s AND x.blocked_id = %1$s)"
                + " AND NOT EXISTS (SELECT 1 FROM vomatt.user_blocks x WHERE x.blocker_id = %1$s AND x.blocked_id = %2$s)")
                .formatted(userColumn, viewerParam);
    }
}
