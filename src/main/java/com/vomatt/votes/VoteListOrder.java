package com.vomatt.votes;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

/**
 * App-facing Poll list orderings (Feed / Explore / Search). Each one fixes both the status filter and the
 * keyset ordering; Scheduled Polls never appear.
 */
public enum VoteListOrder {
    /** Open Polls, most recently opened first (the Feed). */
    NEWEST,
    /** Open Polls, closing soonest first. */
    CLOSING,
    /** Ended Polls, most recently ended first. */
    ENDED;

    /** Resolves {@code status=open|ended} and {@code sort=newest|closing}; defaults to open + newest. */
    public static VoteListOrder of(String status, String sort) {
        if (status == null || status.equals("open")) {
            if (sort == null || sort.equals("newest")) return NEWEST;
            if (sort.equals("closing")) return CLOSING;
            throw ApiException.badRequest(MessageKey.COMMON_INVALID_SORT, sort);
        }
        if (status.equals("ended")) {
            if (sort != null) throw ApiException.badRequest(MessageKey.VOTE_LIST_SORT_NOT_ALLOWED);
            return ENDED;
        }
        throw ApiException.badRequest(MessageKey.COMMON_INVALID_STATUS, status);
    }
}
