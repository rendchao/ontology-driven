package com.jjy.infrastructure.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DateUtil {

    public static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");
    public static final DateTimeFormatter COMPACT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public static final DateTimeFormatter DEFAULT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateUtil() {}

    public static String todayCompact() {
        return LocalDate.now().format(COMPACT);
    }

    public static String nowCompact() {
        return LocalDateTime.now().format(COMPACT_TIME);
    }

    public static String format(LocalDateTime dt) {
        return dt == null ? null : dt.format(DEFAULT);
    }

    public static String formatDate(LocalDate d) {
        return d == null ? null : d.format(DATE);
    }

    public static LocalDateTime parse(String s) {
        return s == null ? null : LocalDateTime.parse(s, DEFAULT);
    }

    public static LocalDate parseDate(String s) {
        return s == null ? null : LocalDate.parse(s, DATE);
    }
}
