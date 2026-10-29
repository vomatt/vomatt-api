package com.vomatt.notifications;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.vomatt.entity.NotificationCategory;
import com.vomatt.entity.NotificationKind;

/**
 * The switches of {@code /api/users/me/notification-preferences}, each turning a set of kinds on or off. A kind switched
 * off is left out of the feed and the unread count. FOLLOWED has no switch, but a category filter can still leave no
 * kind (CONVERSATION with comments off), which callers must check.
 * Stored in {@code user_preferences} under {@link #preferenceKey()}; unset means {@link #onByDefault}.
 */
public enum NotificationPreference {
    RESULTS_IN("resultsIn", true, EnumSet.of(NotificationKind.RESULT, NotificationKind.CANCELLED)),
    INVITATIONS("invitations", true, EnumSet.of(NotificationKind.INVITATION)),
    COMMENTS("comments", true, EnumSet.of(NotificationKind.COMMENT)),
    CLOSING_SOON("closingSoon", false, EnumSet.of(NotificationKind.CLOSING_SOON)),
    LIKES("likes", true, EnumSet.of(NotificationKind.LIKE));

    public static final String KEY_PREFIX = "notifications.";

    private final String field;
    private final boolean onByDefault;
    private final Set<NotificationKind> kinds;

    NotificationPreference(String field, boolean onByDefault, Set<NotificationKind> kinds) {
        this.field = field;
        this.onByDefault = onByDefault;
        this.kinds = kinds;
    }

    public String preferenceKey() {
        return KEY_PREFIX + field;
    }

    /** Every switch at its default. */
    public static Map<NotificationPreference, Boolean> defaults() {
        Map<NotificationPreference, Boolean> settings = new EnumMap<>(NotificationPreference.class);
        for (NotificationPreference preference : values()) {
            settings.put(preference, preference.onByDefault);
        }
        return settings;
    }

    /** Every kind except those whose switch is off; only those of {@code category} when it is not null. */
    public static Set<NotificationKind> enabledKinds(Map<NotificationPreference, Boolean> settings,
                                                     NotificationCategory category) {
        Set<NotificationKind> enabled = EnumSet.allOf(NotificationKind.class);
        if (category != null) {
            enabled.removeIf(kind -> kind.category() != category);
        }
        settings.forEach((preference, on) -> {
            if (!on) {
                enabled.removeAll(preference.kinds);
            }
        });
        return enabled;
    }
}
