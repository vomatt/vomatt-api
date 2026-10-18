package com.vomatt.common.constant;

public final class UserRole {

    private UserRole() {}

    public static final String USER      = "user";
    public static final String MODERATOR = "moderator";
    public static final String ADMIN     = "admin";

    /** Every assignable role; the one list for role validation. */
    public static final String PATTERN   = "user|moderator|admin";

    public static final java.util.List<String> ALL = java.util.List.of(USER, MODERATOR, ADMIN);
}
