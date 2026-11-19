package com.vomatt.entity;

/**
 * A user's paid plan. Each caps the community accounts the user may own (be an OWNER of, not locked), and the members
 * plus pending invitations of a community account whose best-paying OWNER has it, with admin-tunable {@code config}
 * lookups, falling back to the defaults here. Declared from lowest to highest.
 */
public enum Plan {
    FREE("community_limit_free", 1, "community_member_limit_free", 5),
    PRO("community_limit_pro", 3, "community_member_limit_pro", 10);

    private final String limitKey;
    private final int defaultLimit;
    private final String memberLimitKey;
    private final int defaultMemberLimit;

    Plan(String limitKey, int defaultLimit, String memberLimitKey, int defaultMemberLimit) {
        this.limitKey = limitKey;
        this.defaultLimit = defaultLimit;
        this.memberLimitKey = memberLimitKey;
        this.defaultMemberLimit = defaultMemberLimit;
    }

    public String memberLimitKey() {
        return memberLimitKey;
    }

    public int defaultMemberLimit() {
        return defaultMemberLimit;
    }

    public String limitKey() {
        return limitKey;
    }

    public int defaultLimit() {
        return defaultLimit;
    }
}
