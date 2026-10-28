package com.xingchen.huibiao.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表格里的值大多不可靠：数字带单位、日期有五六种写法、表头夹着空格和括号。
 * 这里集中处理，业务代码里不再散落一堆 try/catch。
 */
public final class TextUtils {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    /**
     * 从别的系统复制粘贴、或者导出时编码不对，会留下这些典型残字。
     */
    private static final Pattern GARBLED = Pattern.compile("锟斤拷|烫烫烫|屯屯屯|ï¿½|\\?{3,}");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TextUtils() {
    }

    public static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 表头归一化：全角转半角，去掉空格、括号、冒号、下划线。
     */
    public static String norm(String value) {
        if (value == null) {
            return "";
        }
        String s = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString()
                .replaceAll("[\\s()（）\\[\\]【】{}<>《》:：,，.。;；'\"“”‘’_\\-—/\\\\|*#]", "");
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())
                || "—".equals(value.trim()) || "-".equals(value.trim());
    }

    /**
     * 判断一段文字里有没有编码损坏的痕迹。真正的乱码没法还原，只能挑出来交给人工。
     */
    public static boolean garbled(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return value.indexOf('\uFFFD') >= 0 || GARBLED.matcher(value).find();
    }

    /**
     * 取第一段数字。"2.5小时" -> 2.5，"共 3 天" -> 3，空值返回 null。
     */
    public static Double parseNumber(String value) {
        if (isBlank(value)) {
            return null;
        }
        String s = value.replace(",", "").replace("，", "").replace(" ", "");
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static double numberOrZero(String value) {
        Double d = parseNumber(value);
        return d == null ? 0d : d;
    }

    public static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    public static String numberText(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.valueOf(round1(value));
    }

    /**
     * 兼容 4/1/26、2026-04-27、2026/4/27、2026年4月1日 这几种写法。
     */
    public static LocalDate parseDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        String s = value.trim().replace("年", "-").replace("月", "-").replace("日", "")
                .replace("/", "-").replace(".", "-");
        String[] parts = s.split("-");
        if (parts.length < 3) {
            return null;
        }
        try {
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            int c = Integer.parseInt(parts[2].trim());
            int year;
            int month;
            int day;
            if (a >= 1900) {
                year = a;
                month = b;
                day = c;
            } else {
                month = a;
                day = b;
                year = c >= 100 ? c : 2000 + c;
            }
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把日期和 "20:00" 拼成 HR 能接受的 yyyy-MM-dd HH:mm。
     * 时间缺失时补 00:00，宁可让人复核，也不猜业务规则。
     */
    public static String combineDateTime(String date, String time) {
        LocalDate d = parseDate(date);
        if (d == null) {
            return null;
        }
        LocalTime t = LocalTime.MIDNIGHT;
        if (!isBlank(time)) {
            Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(time);
            if (m.find()) {
                int hour = Math.min(23, Integer.parseInt(m.group(1)));
                int minute = Math.min(59, Integer.parseInt(m.group(2)));
                t = LocalTime.of(hour, minute);
            }
        }
        return LocalDateTime.of(d, t).format(DATE_TIME);
    }

    public static String formatDate(LocalDate date) {
        return date == null ? null : date.format(DATE);
    }

    public static String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME);
    }

    /**
     * 只保留文件名，防止下载接口被 ../ 穿越。
     */
    public static String safeName(String name) {
        if (name == null) {
            return "file";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
