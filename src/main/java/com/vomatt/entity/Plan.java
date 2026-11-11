package com.vomatt.entity;

/**
 * A user's paid plan. Each caps the community accounts the user may own (be an OWNER of, not locked) with an admin-tunable
 * {@code config} lookup, falling back to the default here.
 */
public enum Plan {
    FREE("community_limit_free", 1),
    PRO("community_limit_pro", 3);

    private final String limitKey;
    private final int defaultLimit;

    Plan(String limitKey, int defaultLimit) {
        this.limitKey = limitKey;
        this.defaultLimit = defaultLimit;
    }

    public String limitKey() {
        return limitKey;
    }

    public int defaultLimit() {
        return defaultLimit;
    }
}
