package com.vomatt.common.util;

/** Normalization for optional user-typed text such as query parameters. */
public final class TextUtil {

    private TextUtil() {}

    /** Stripped text, or null when null or blank, so whitespace-only means "not given". */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
