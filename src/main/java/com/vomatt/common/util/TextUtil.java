package com.vomatt.common.util;

/** Normalization for optional user-typed text such as query parameters. */
public final class TextUtil {

    private TextUtil() {}

    /** Stripped text, or null when null or blank, so whitespace-only means "not given". */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** For logs and admin lists: {@code a***@domain}; anything without a local part becomes {@code ***}. */
    public static String maskEmail(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    /** For logs and admin lists: the first 3 characters (country code) and the last 2 digits, e.g. {@code +88****78}. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 2);
    }
}
