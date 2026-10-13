package com.vomatt.common.response;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

/**
 * Keyset position of the last item on a page: the sort key plus the id as tie-breaker.
 * Clients treat the encoded form as opaque (see ADR 0002).
 */
public record Cursor(String key, UUID id) {

    /**
     * The list and ordering a cursor was issued for; a cursor is only accepted by the same scope.
     * Filters ({@code tag}, {@code q}) and the parent id are not part of it: the keyset position stays valid under them.
     */
    public enum Scope {
        FEED_NEWEST, FEED_CLOSING, FEED_ENDED, MY_OPEN, MY_ENDED, USER_POLLS,
        COMMENTS, REPLIES, VOTERS, USER_SEARCH, POPULAR_TAGS
    }

    public static Cursor of(OffsetDateTime time, UUID id) {
        return new Cursor(time.toInstant().toString(), id);
    }

    public static Cursor of(long value, UUID id) {
        return new Cursor(Long.toString(value), id);
    }

    public static Cursor of(String value, UUID id) {
        return new Cursor(value, id);
    }

    // A client may send a cursor from another list; a key of the wrong kind is a bad cursor, not a 500
    public OffsetDateTime timeKey() {
        try {
            return Instant.parse(key).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(MessageKey.COMMON_CURSOR_INVALID);
        }
    }

    public long longKey() {
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(MessageKey.COMMON_CURSOR_INVALID);
        }
    }

    public String encode(Scope scope) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + "|" + id + "|" + key).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a client-supplied cursor; null stays null (first page).
     * A cursor issued for another list or ordering, or one without a scope, is a bad cursor.
     */
    public static Cursor decode(String token, Scope expected) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", 3);
            if (parts.length < 3 || !parts[0].equals(expected.name())) {
                throw ApiException.badRequest(MessageKey.COMMON_CURSOR_INVALID);
            }
            return new Cursor(parts[2], UUID.fromString(parts[1]));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(MessageKey.COMMON_CURSOR_INVALID);
        }
    }
}
