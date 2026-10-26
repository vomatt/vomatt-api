package com.vomatt.notifications;

import java.util.Locale;
import java.util.UUID;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;
import com.vomatt.entity.NotificationKind;

/**
 * Public id of a notification: its kind and the id of its source row, written {@code <kind>_<sourceId>} (e.g.
 * {@code comment_0199c1a2-...}). Never a bare UUID, so {@code POST /api/notifications/{id}/read} tells it apart from the
 * deprecated Poll id.
 */
public record NotificationId(NotificationKind kind, UUID sourceId) {

    /** A malformed id names no notification of the caller's, so it is 404 like an unknown one. */
    public static NotificationId parse(String value) {
        int separator = value.lastIndexOf('_');
        try {
            return new NotificationId(NotificationKind.valueOf(value.substring(0, separator).toUpperCase(Locale.ROOT)),
                    UUID.fromString(value.substring(separator + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw ApiException.notFound(MessageKey.NOTIFICATION_NOT_FOUND);
        }
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + "_" + sourceId;
    }
}
