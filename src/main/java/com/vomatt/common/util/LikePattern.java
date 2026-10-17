package com.vomatt.common.util;

/** LIKE patterns for user-typed search text; use with {@code ESCAPE '\'}. */
public final class LikePattern {

    private LikePattern() {}

    /** Case-insensitive "contains" pattern: lower-cased, with % _ and \ matched literally, wrapped in %. */
    public static String containsPattern(String text) {
        return "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
