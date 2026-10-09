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
    EMAIL("email", false),
    FIRST_NAME("firstName", false),
    LAST_NAME("lastName", false),
    LOCATION("location", false),
    POINTS("points", false),
    DISPLAY_NAME("displayName", true),
    BIO("bio", true),
    MEMBERSHIP_LEVEL("membershipLevel", false);

    private final String fieldName;
    private final boolean visibleByDefault;

    private static final String PREFERENCE_PREFIX = "visibility.";

    private static final Map<String, VisibilityField> BY_FIELD_NAME =
            Arrays.stream(values())
                    .collect(Collectors.toMap(VisibilityField::getFieldName, Function.identity()));

    VisibilityField(String fieldName, boolean visibleByDefault) {
        this.fieldName = fieldName;
        this.visibleByDefault = visibleByDefault;
    }

    public String getFieldName() {
        return fieldName;
    }

    /**
     * Whether the field is public until the user sets otherwise
     */
    public boolean isVisibleByDefault() {
        return visibleByDefault;
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
