package com.vomatt.entity;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

import java.util.Locale;

/** Poll lifecycle state, derived from start and end time (see CONTEXT.md). */
public enum VoteStatus {
    SCHEDULED,  // start time not reached: visible, no Ballots
    OPEN,       // accepting Ballots, changes and Retractions
    ENDED;      // end time passed or Closed; takes precedence over SCHEDULED

    /** The {@code status} query parameter (lower case); anything else is 400 {@code common.invalid_status}. */
    public static VoteStatus parse(String status) {
        for (VoteStatus value : values()) {
            if (value.name().toLowerCase(Locale.ROOT).equals(status)) {
                return value;
            }
        }
        throw ApiException.badRequest(MessageKey.COMMON_INVALID_STATUS, status);
    }
}
