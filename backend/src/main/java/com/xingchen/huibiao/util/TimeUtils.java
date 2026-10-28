package com.xingchen.huibiao.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class TimeUtils {

    public static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private TimeUtils() {
    }

    public static String nowMinute() {
        return LocalDateTime.now().format(MINUTE);
    }

    public static String nowSecond() {
        return LocalDateTime.now().format(SECOND);
    }

    public static String clock() {
        return LocalDateTime.now().format(CLOCK);
    }
}
