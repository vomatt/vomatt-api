package com.vomatt.common.response;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import com.vomatt.common.exception.ApiException;
import com.vomatt.common.i18n.MessageKey;

/**
 * Keyset position of the last item on a page: the sort key plus the id as tie-breaker.
 * Clients treat the encoded form as opaque (see ADR 0002).
 */
public record Cursor(String key, UUID id) {

    public static Cursor of(OffsetDateTime time, UUID id) {
        return new Cursor(time.toInstant().toString(), id);
    }

    public static Cursor of(long value, UUID id) {
        return new Cursor(Long.toString(value), id);
    }

    public static Cursor of(String value, UUID id) {
        return new Cursor(value, id);
    }

    public OffsetDateTime timeKey() {
        return Instant.parse(key).atOffset(ZoneOffset.UTC);
    }

    public long longKey() {
        return Long.parseLong(key);
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((id + "|" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes a client-supplied cursor; null stays null (first page). */
    public static Cursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            return new Cursor(raw.substring(sep + 1), UUID.fromString(raw.substring(0, sep)));
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            throw ApiException.badRequest(MessageKey.COMMON_CURSOR_INVALID);
        }
    }
}
