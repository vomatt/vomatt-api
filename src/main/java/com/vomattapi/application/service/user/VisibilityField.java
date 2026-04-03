package com.vomattapi.application.service.user;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 可控制顯示的個人資料欄位
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
     * 偏好設定的 key，如 "visibility.email"
     */
    public String preferenceKey() {
        return PREFERENCE_PREFIX + fieldName;
    }

    /**
     * 從欄位名稱查找對應的 VisibilityField
     */
    public static Optional<VisibilityField> fromFieldName(String fieldName) {
        return Optional.ofNullable(BY_FIELD_NAME.get(fieldName));
    }

    /**
     * 從偏好設定 key 解析欄位名稱（去除 "visibility." 前綴）
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
