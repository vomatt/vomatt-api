package com.vomatt.common.constant;

import java.util.List;

/** Values accepted by the admin unlock-login API; the one list for its validation and its options endpoint. */
public final class LoginUnlock {

    private LoginUnlock() {}

    /** Consumer email OTP login */
    public static final String CHANNEL_EMAIL = "email";
    /** Consumer phone OTP login */
    public static final String CHANNEL_PHONE = "phone";
    /** Admin console email OTP login */
    public static final String CHANNEL_ADMIN = "admin";

    public static final List<String> CHANNELS = List.of(CHANNEL_EMAIL, CHANNEL_PHONE, CHANNEL_ADMIN);
    public static final String CHANNEL_PATTERN = "email|phone|admin";

    /** Lockout, its doubling level and the per-IP wrong-code counter */
    public static final String TARGET_LOCK = "lock";
    /** Wrong-code counters (per IP); also discards the current OTP, so the user requests a new one */
    public static final String TARGET_ATTEMPTS = "attempts";
    /** Resend cooldown, including the 15-minute one after an OTP's guesses run out */
    public static final String TARGET_COOLDOWN = "cooldown";

    public static final List<String> TARGETS = List.of(TARGET_LOCK, TARGET_ATTEMPTS, TARGET_COOLDOWN);
    public static final String TARGET_PATTERN = "lock|attempts|cooldown";
}
