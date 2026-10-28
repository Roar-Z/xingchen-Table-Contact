package com.xingchen.huibiao.service;

import com.xingchen.huibiao.dto.ApiRequests.MappingEntry;
import com.xingchen.huibiao.dto.ApiRequests.TaskConfig;
import com.xingchen.huibiao.util.TextUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标列的“拼接/派生”表达式。
 *
 * 源表列不够用时，允许一条映射不指向单个源列，而是写成表达式：
 *   {起始日期} {起始时间}   → 2026-04-27 20:00
 *   "加班"                 → 常量，引号可省略
 *   {天}*8                 → 按每天 8 小时换算
 * 花括号里必须是该源表真实存在的列名，猜不出来就整条丢弃，不会瞎填。
 */
public final class ColumnExpressions {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern MULTIPLY = Pattern.compile("^\\{\\s*([^{}]+?)\\s*}\\s*\\*\\s*(\\d+(?:\\.\\d+)?)$");
    /** 日期 + 时刻：`{起始日期} + {起始时间}`，合并成标准日期时间（yyyy-MM-dd HH:mm） */
    private static final Pattern DATE_TIME_SPLICE = Pattern.compile(
            "^\\{\\s*([^{}]+?)\\s*}\\s*\\+\\s*\\{\\s*([^{}]+?)\\s*}$");
    private static final int MAX_LENGTH = 200;

    private final Map<String, String> byTarget;

    private ColumnExpressions(Map<String, String> byTarget) {
        this.byTarget = byTarget;
    }

    public static ColumnExpressions of(TaskConfig config) {
        Map<String, String> map = new LinkedHashMap<>();
        if (config != null && config.mapping() != null) {
            for (MappingEntry entry : config.mapping()) {
                if (entry.target() == null || TextUtils.isBlank(entry.expr())) {
                    continue;
                }
                map.put(entry.target().trim(), entry.expr().trim());
            }
        }
        return new ColumnExpressions(map);
    }

    public static ColumnExpressions empty() {
        return new ColumnExpressions(Collections.emptyMap());
    }

    /** 这个目标列是否有表达式（有的话就不再按内部字段取值）。 */
    public String exprOf(String target) {
        return target == null ? null : byTarget.get(target);
    }

    public boolean hasExpr(String target) {
        return target != null && byTarget.containsKey(target);
    }

    public Set<String> targets() {
        return byTarget.keySet();
    }

    /** 表达式里引用到的源表列名。 */
    public static Set<String> placeholders(String expr) {
        Set<String> set = new LinkedHashSet<>();
        if (expr == null) {
            return set;
        }
        Matcher matcher = PLACEHOLDER.matcher(expr);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                set.add(name);
            }
        }
        return set;
    }

    /**
     * 校验表达式：长度可控、引用的列都在给定表头里、不是空壳。
     * 给 AI 建议做白名单用，手写的表达式也走同一套检查。
     */
    public static boolean isValid(String expr, Set<String> headers) {
        if (TextUtils.isBlank(expr) || expr.length() > MAX_LENGTH) {
            return false;
        }
        Set<String> used = placeholders(expr);
        if (used.isEmpty()) {
            // 没有占位符时只允许常量，避免把随机文字当成表达式
            return !stripQuotes(expr).isBlank();
        }
        return headers != null && headers.containsAll(used);
    }

    /**
     * 按一行数据算出单元格值；引用的列不存在时返回 null，调用方退回原来的取值方式。
     */
    public static String render(String expr, Map<String, String> row) {
        if (TextUtils.isBlank(expr) || row == null) {
            return null;
        }
        Matcher multiply = MULTIPLY.matcher(expr.trim());
        if (multiply.matches()) {
            String column = multiply.group(1).trim();
            if (!row.containsKey(column)) {
                return null;
            }
            Double value = TextUtils.parseNumber(row.get(column));
            if (value == null) {
                return "";
            }
            double scale = Double.parseDouble(multiply.group(2));
            double result = TextUtils.round1(value * scale);
            return Math.abs(result - Math.rint(result)) < 0.0001
                    ? String.valueOf((long) Math.rint(result))
                    : String.valueOf(result);
        }

        // 日期 + 时刻：合并成标准日期时间，格式与业务代码的 combineDateTime 保持一致
        Matcher splice = DATE_TIME_SPLICE.matcher(expr.trim());
        if (splice.matches()) {
            String dateColumn = splice.group(1).trim();
            String timeColumn = splice.group(2).trim();
            if (!row.containsKey(dateColumn) || !row.containsKey(timeColumn)) {
                return null;
            }
            return TextUtils.combineDateTime(row.get(dateColumn), row.get(timeColumn));
        }

        Matcher matcher = PLACEHOLDER.matcher(expr);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(expr, last, matcher.start());
            String column = matcher.group(1).trim();
            if (!row.containsKey(column)) {
                return null;
            }
            out.append(TextUtils.trim(row.get(column)));
            last = matcher.end();
        }
        out.append(expr.substring(last));
        String text = stripQuotes(out.toString()).replaceAll("\\s+", " ").trim();
        return text;
    }

    private static String stripQuotes(String text) {
        return text.replace("\"", "").replace("'", "").trim();
    }
}
