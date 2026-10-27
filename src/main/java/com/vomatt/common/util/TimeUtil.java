package com.vomatt.common.util;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

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
    public static LocalDate taipeiBusinessDate(OffsetDateTime instant) {
        return instant.atZoneSameInstant(TAIPEI_ZONE).toLocalDate();
    }
}
