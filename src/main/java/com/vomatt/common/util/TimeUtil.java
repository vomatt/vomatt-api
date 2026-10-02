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
     * 依台北時區（UTC+8）認定「營業日」（M1 出勤打卡用）。
     *
     * <p>JVM 未設定預設時區（{@code application.yml} 無 {@code time-zone} 設定），
     * 故不可依賴 {@code LocalDate.now()}/系統預設時區，需顯式轉換至 {@code Asia/Taipei}。
     * {@code instant} 為任一時區的即時時刻（{@link OffsetDateTime} 內部皆為同一瞬間），
     * 轉換後取台北當地日期。</p>
     */
    public static LocalDate taipeiBusinessDate(OffsetDateTime instant) {
        return instant.atZoneSameInstant(TAIPEI_ZONE).toLocalDate();
    }

    private static final DateTimeFormatter TAIPEI_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 任一時區的即時時刻 → 台北當地時間 "HH:mm"（M1 出勤打卡顯示用）。 */
    public static String taipeiTimeOfDay(OffsetDateTime instant) {
        return instant.atZoneSameInstant(TAIPEI_ZONE).format(TAIPEI_TIME_FORMAT);
    }
}
