package com.xingchen.huibiao.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// 时间格式化就这两处，别的类不要自己写一遍格式串
public final class TimeUtils {

    public static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private TimeUtils() {
    }

    public static String nowSecond() {
        return LocalDateTime.now().format(SECOND);
    }

    public static String clock() {
        return LocalDateTime.now().format(CLOCK);
    }
}
