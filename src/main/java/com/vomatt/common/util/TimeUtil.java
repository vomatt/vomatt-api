package com.vomatt.common.util;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class TimeUtil {

    private TimeUtil() {
    }

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    /**
     * The calendar date in Taipei (UTC+8) at {@code instant}.
     *
     * <p>The JVM has no default time zone configured ({@code application.yml} has no {@code time-zone} setting),
     * so do not rely on {@code LocalDate.now()} / the system default time zone; convert explicitly to {@code Asia/Taipei}.</p>
     */
    /** A timestamptz column read by a native query, whichever type the driver hands back. */
    public static OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case OffsetDateTime time -> time;
            case Instant instant -> instant.atOffset(ZoneOffset.UTC);
            case Timestamp timestamp -> timestamp.toInstant().atOffset(ZoneOffset.UTC);
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }

    public static LocalDate taipeiBusinessDate(OffsetDateTime instant) {
        return instant.atZoneSameInstant(TAIPEI_ZONE).toLocalDate();
    }
}
