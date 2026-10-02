package com.vomatt.users;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Controllable personal profile fields for visibility settings
 */
public enum VisibilityField {
    EMAIL("email"),
    FIRST_NAME("firstName"),
    LAST_NAME("lastName"),
    LOCATION("location"),
    POINTS("points"),
    DISPLAY_NAME("displayName"),
    BIO("bio"),
    MEMBERSHIP_LEVEL("membershipLevel");

    private final String fieldName;

    private static final String PREFERENCE_PREFIX = "visibility.";

    private static final Map<String, VisibilityField> BY_FIELD_NAME =
            Arrays.stream(values())
                    .collect(Collectors.toMap(VisibilityField::getFieldName, Function.identity()));

    VisibilityField(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }

    /**
     * Preference setting key, e.g., "visibility.email"
     */
    public String preferenceKey() {
        return PREFERENCE_PREFIX + fieldName;
    }

    /**
     * Find the corresponding VisibilityField from field name
     */
    public static Optional<VisibilityField> fromFieldName(String fieldName) {
        return Optional.ofNullable(BY_FIELD_NAME.get(fieldName));
    }

    /**
     * Extract field name from preference key (remove "visibility." prefix)
     */
    public static String extractFieldName(String preferenceKey) {
        return preferenceKey.startsWith(PREFERENCE_PREFIX)
                ? preferenceKey.substring(PREFERENCE_PREFIX.length())
                : preferenceKey;
    }

    public static String getPreferencePrefix() {
        return PREFERENCE_PREFIX;
    }
}
