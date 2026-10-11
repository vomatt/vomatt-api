package com.vomatt.common.util;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeUtil {

    private TimeUtil() {
    }

    /** "HH:MM" → minutes since midnight */
    public static int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /** minutes since midnight → "HH:MM" */
    public static String minutesToTime(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

    /**
     * Determines the "business day" in the Taipei time zone (UTC+8) (used by M1 attendance clock-in).
     *
     * <p>The JVM has no default time zone configured ({@code application.yml} has no {@code time-zone} setting),
     * so do not rely on {@code LocalDate.now()} / the system default time zone; convert explicitly to {@code Asia/Taipei}.
     * {@code instant} is a point in time in any time zone ({@link OffsetDateTime} always represents the same instant internally),
     * converted to the local date in Taipei.</p>
     */
    public static LocalDate taipeiBusinessDate(OffsetDateTime instant) {
        return instant.atZoneSameInstant(TAIPEI_ZONE).toLocalDate();
    }

    private static final DateTimeFormatter TAIPEI_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** Point in time in any time zone -> Taipei local time "HH:mm" (for M1 attendance clock-in display). */
    public static String taipeiTimeOfDay(OffsetDateTime instant) {
        return instant.atZoneSameInstant(TAIPEI_ZONE).format(TAIPEI_TIME_FORMAT);
    }
}
