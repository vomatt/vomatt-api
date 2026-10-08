package com.vomatt.votes;

import java.util.Locale;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

/** {@code ?status=} on the public vote list. Scheduled and cancelled votes are never listed. */
public enum VoteListStatus {
    OPEN, ENDED, ALL;

    /** Parses a query value case-insensitively; null means OPEN, an unknown value is a 400. */
    public static VoteListStatus fromParam(String value) {
        if (value == null || value.isBlank()) return OPEN;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(MessageKey.COMMON_TYPE_MISMATCH, "status");
        }
    }
}
