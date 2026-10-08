package com.vomatt.votes;

import java.util.Locale;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

import org.springframework.data.domain.Sort;

/** {@code ?sort=} on the public vote list: newest first, most participants, or soonest end. */
public enum VoteListSort {
    NEWEST(Sort.by(Sort.Order.desc("createdAt"))),
    /** Ordered inside the query (participant count), so the pageable stays unsorted. */
    POPULAR(Sort.unsorted()),
    /** PostgreSQL puts NULL last on ASC, so votes without an end time come last. */
    ENDING(Sort.by(Sort.Order.asc("endTime"), Sort.Order.desc("createdAt")));

    private final Sort order;

    VoteListSort(Sort order) {
        this.order = order;
    }

    public Sort order() {
        return order;
    }

    /** Parses a query value case-insensitively; null means NEWEST, an unknown value is a 400. */
    public static VoteListSort fromParam(String value) {
        if (value == null || value.isBlank()) return NEWEST;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(MessageKey.COMMON_TYPE_MISMATCH, "sort");
        }
    }
}
