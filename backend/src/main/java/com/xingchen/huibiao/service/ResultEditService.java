package com.xingchen.huibiao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.model.ProcessResult;
import com.xingchen.huibiao.model.ResultRevision;
import com.xingchen.huibiao.model.TaskRecord;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结果表的“自然语言修改”。
 *
 * 用户写一句“第 3 行开始时间改成 09:00”，这里先把它翻译成结构化操作
 * （set / math / delete / sort / add_column / drop_column），再按顺序作用在
 * 重新算出来的结果行上。行号、条件、列名都由确定性代码执行，模型只负责听懂话。
 *
 * 修改记录存在 task.config.edits 里：重新执行时按原顺序重放，所以下载到的文件和
 * 屏幕上的预览始终是同一份结果。
 */
@Service
public class ResultEditService {

    private static final Logger log = LoggerFactory.getLogger(ResultEditService.class);
    /** 给模型看的样例行数，控制 prompt 长度。 */
    private static final int SAMPLE_ROWS = 6;
    /** 一个任务最多保留多少条修改记录，避免配置无限膨胀。 */
    public static final int MAX_REVISIONS = 20;

    private static final Set<String> TYPES = Set.of(
            "set", "math", "delete", "sort", "add_column", "drop_column");

    private static final Pattern ROW_NO = Pattern.compile("第\\s*(\\d{1,4})\\s*行");
    private static final Pattern SET_VERB = Pattern.compile(
            "改成|改为|设成|设为|变成|换成|填成|填为|写成|改到|修改为|调整为");
    private static final Pattern MATH_VERB = Pattern.compile(
            "(加上|加|减去|减|乘以|乘|除以)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern COND = Pattern.compile(
            "\\s*(?:列)?\\s*(不包含|包含|含有|不为空|有值|为空|是空|没有值|大于|超过|高于|小于|低于|不低于|不少于|为|是|等于|=|>|<)\\s*"
                    + "([^\\s，,。；;]{1,30}?)(?=的|\\s|，|,|。|；|;|$)");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?:新增|增加|添加|加)\\s*(?:一列|1列|列)\\s*[「“\"']?([^「」“”\"'\\s，,。；;]{1,12})");
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "(?:删除|删掉|去掉|移除)\\s*(?:一列|该列|这一列|列)\\s*[「“\"']?([^「」“”\"'\\s，,。；;]{1,12})");
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\d+(?:\\.\\d+)?$");

    private final ObjectMapper mapper;
    private final ObjectProvider<ChatModel> chatModel;

    public ResultEditService(ObjectMapper mapper, ObjectProvider<ChatModel> chatModel) {
        this.mapper = mapper;
        this.chatModel = chatModel;
    }

    /* ==================== 听懂一句话 ==================== */

    public ResultRevision parse(String instruction, TaskRecord task) {
        if (TextUtils.isBlank(instruction)) {
            throw new IllegalArgumentException("先说说结果要怎么改，比如「第 3 行开始时间改成 09:00」");
        }
        List<String> columns = task.outputColumns == null ? List.of() : task.outputColumns;
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("这个任务还没有结果列，先让任务跑出结果再改");
        }

        ResultRevision revision = new ResultRevision();
        revision.id = "R" + (System.currentTimeMillis() % 100000);
        revision.instruction = instruction.trim();
        revision.createdAt = TimeUtils.clock();

        ChatModel model = chatModel.getIfAvailable();
        if (model != null) {
            try {
                List<Map<String, Object>> ops = parseByModel(model, revision.instruction, task, columns);
                if (!ops.isEmpty()) {
                    revision.ops = ops;
                    revision.mode = "llm";
                }
            } catch (Exception e) {
                log.warn("语义模型没听懂这句修改要求，退回句式解析：{}", e.getMessage());
            }
        }
        if (revision.ops.isEmpty()) {
            revision.ops = parseByRules(revision.instruction, columns);
            revision.mode = "rules";
        }
        if (revision.ops.isEmpty()) {
            throw new IllegalArgumentException("没听懂这句要求，可以换成「第 3 行的开始时间改成 09:00」"
                    + "「姓名为张三的行删掉」「合计小时全部加 1」「按工号排序」这种说法");
        }
        validate(revision.ops, columns);
        if (TextUtils.isBlank(revision.summary)) {
            revision.summary = summarize(revision.ops);
        }
        return revision;
    }

    private List<Map<String, Object>> parseByModel(ChatModel model, String instruction,
                                                   TaskRecord task, List<String> columns) throws Exception {
        String prompt = """
                你是企业表格结果编辑助手。用户对已经生成的结果表提出一句修改要求，
                你要把它翻译成确定性的操作指令，由程序去执行，你不要自己算数。
                只输出 JSON，不要解释，不要输出 Markdown。
                输出格式：
                {"summary":"一句话复述你理解到的修改","ops":[操作对象...]}

                操作类型（ops 里按顺序执行）：
                1. 改值：{"type":"set","column":"列名","value":"新值","scope":范围}
                2. 数值运算：{"type":"math","column":"列名","op":"add 或 sub 或 mul 或 div","value":1.5,"scope":范围}
                3. 删除行：{"type":"delete","scope":范围}
                4. 排序：{"type":"sort","column":"列名","order":"asc 或 desc"}
                5. 新增列：{"type":"add_column","column":"新列名","template":"{列名}-{列名} 或常量文本"}
                6. 删除列：{"type":"drop_column","column":"列名"}

                范围 scope 三种写法，只能选一种：
                {"row":3}               第 3 行（按结果表当前顺序，从 1 开始）
                {"all":true}            全部行
                {"where":[{"column":"列名","op":"eq","value":"值"}]}  满足条件的行，多个conditions是并且关系
                where 的 op 只能是 eq、ne、contains、not_contains、empty、not_empty、gt、lt、gte、lte。

                硬性要求：
                1. column 必须逐字来自下面的“结果表列”，不要翻译、不要编造。
                2. 用户没给行号也没给条件时用 {"all":true}；用户说“这些行/这批”而没给条件时也用 all。
                3. 不要计算任何结果，只描述怎么改。
                4. 听不懂、或者要求要改源表/重新映射时，输出 {"summary":"","ops":[]}。

                结果表列：
                %s

                当前结果（行号 | 各列值）：
                %s

                用户要求：
                %s
                """.formatted(String.join("、", columns), describeRows(task, columns), instruction);

        String answer = model.chat(prompt);
        JsonNode node = mapper.readTree(stripJson(answer));
        List<Map<String, Object>> ops = new ArrayList<>();
        JsonNode opsNode = node.path("ops");
        if (opsNode.isArray()) {
            for (JsonNode item : opsNode) {
                Map<String, Object> op = plain(item);
                if (!TextUtils.isBlank(text(op.get("type")))) {
                    ops.add(op);
                }
            }
        }
        return ops;
    }

    /**
     * 没有模型时按常见句式兜底。覆盖“第 N 行/某列等于某值 + 改成”、加减、删除、排序、加列。
     */
    private List<Map<String, Object>> parseByRules(String instruction, List<String> columns) {
        String text = instruction.trim();
        List<Map<String, Object>> ops = new ArrayList<>();

        // 删除行：删/去掉/移除 + 行
        if ((text.contains("删") || text.contains("去掉") || text.contains("移除"))
                && (text.contains("行") || text.contains("记录") || text.contains("条目"))) {
            ops.add(op("delete", "scope", scopeOf(text, columns)));
            return ops;
        }

        // 排序
        if (text.contains("排序") || text.contains("重排") || text.contains("排一下")) {
            String column = columnIn(text, columns);
            if (column != null) {
                boolean desc = text.contains("降序") || text.contains("倒序") || text.contains("从大到小");
                ops.add(op("sort", "column", column, "order", desc ? "desc" : "asc"));
                return ops;
            }
        }

        // 新增列
        Matcher add = ADD_COLUMN.matcher(text);
        if (add.find() && (text.contains("新增") || text.contains("增加") || text.contains("添加"))) {
            String column = add.group(1);
            String template = templateOf(text, columns);
            ops.add(op("add_column", "column", column, "template", template));
            return ops;
        }

        // 删除列
        Matcher drop = DROP_COLUMN.matcher(text);
        if (drop.find()) {
            ops.add(op("drop_column", "column", drop.group(1)));
            return ops;
        }

        // 数值加减
        Matcher math = MATH_VERB.matcher(text);
        if (math.find()) {
            String column = columnBefore(text, math.start(), columns);
            String verb = math.group(1);
            String op = verb.startsWith("加") ? "add" : verb.startsWith("减") ? "sub"
                    : verb.startsWith("乘") ? "mul" : "div";
            if (column != null) {
                Map<String, Object> item = op("math", "column", column, "op", op,
                        "value", Double.parseDouble(math.group(2)));
                item.put("scope", scopeOf(text, columns));
                ops.add(item);
                return ops;
            }
        }

        // 改值：找到“改成/设为”前面的那一列
        Matcher set = SET_VERB.matcher(text);
        if (set.find()) {
            String column = columnBefore(text, set.start(), columns);
            if (column != null) {
                String value = text.substring(set.end()).trim()
                        .replaceAll("^[的为成]\\s*", "")
                        .replaceAll("[。！!，,]+$", "")
                        .trim();
                value = stripQuotes(value);
                if (!value.isBlank()) {
                    Map<String, Object> item = op("set", "column", column, "value", value);
                    item.put("scope", scopeOf(text, columns));
                    ops.add(item);
                    return ops;
                }
            }
        }
        return ops;
    }

    /* ==================== 校验与描述 ==================== */

    private void validate(List<Map<String, Object>> ops, List<String> columns) {
        for (Map<String, Object> op : ops) {
            String type = text(op.get("type"));
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("没听懂要怎么改（不支持的操作：" + type + "）");
            }
            if (!"delete".equals(type)) {
                String column = text(op.get("column"));
                if (column.isBlank()) {
                    throw new IllegalArgumentException("这句话里没看出要改哪一列，现有列：" + String.join("、", columns));
                }
                boolean exists = columns.contains(column);
                if ("add_column".equals(type) && exists) {
                    throw new IllegalArgumentException("「" + column + "」已经是一列了，直接说怎么改它的值就行");
                }
                if (!"add_column".equals(type) && !exists) {
                    throw new IllegalArgumentException("结果表里没有「" + column + "」这一列，现有列："
                            + String.join("、", columns));
                }
            }
            if ("delete".equals(type) && isAll(op.get("scope"))) {
                throw new IllegalArgumentException("删除整张结果表没有意义，指定第几行或者筛选条件更稳妥");
            }
            if ("math".equals(type)) {
                String mathOp = text(op.get("op"));
                if (!Set.of("add", "sub", "mul", "div").contains(mathOp)) {
                    throw new IllegalArgumentException("数值运算只支持加、减、乘、除");
                }
            }
            if ("sort".equals(type) && !Set.of("asc", "desc").contains(text(op.get("order")))) {
                op.put("order", "asc");
            }
            if ("add_column".equals(type) && text(op.get("template")).isBlank()) {
                op.put("template", "");
            }
        }
    }

    public String describe(TaskRecord task) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> revision : rawRevisions(task)) {
            String summary = text(revision.get("summary"));
            if (!summary.isBlank()) {
                parts.add(summary);
            }
        }
        return String.join("；", parts);
    }

    private String summarize(List<Map<String, Object>> ops) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            String type = text(op.get("type"));
            String column = text(op.get("column"));
            String scope = scopeText(op.get("scope"));
            switch (type) {
                case "set" -> parts.add(scope + "「" + column + "」改成 " + text(op.get("value")));
                case "math" -> parts.add(scope + "「" + column + "」" + mathText(text(op.get("op")),
                        text(op.get("value"))));
                case "delete" -> parts.add("删除" + scope + "的行");
                case "sort" -> parts.add("按「" + column + "」" + ("desc".equals(text(op.get("order")))
                        ? "降序" : "升序") + "重排");
                case "add_column" -> parts.add("新增列「" + column + "」，值按 " + text(op.get("template")) + " 生成");
                case "drop_column" -> parts.add("删除列「" + column + "」");
                default -> {
                }
            }
        }
        return String.join("；", parts);
    }

    private String mathText(String op, String value) {
        return switch (op) {
            case "sub" -> "减去 " + value;
            case "mul" -> "乘以 " + value;
            case "div" -> "除以 " + value;
            default -> "加上 " + value;
        };
    }

    private String scopeText(Object scope) {
        if (scope instanceof Map<?, ?> map) {
            if (map.get("row") instanceof Number number) {
                return "第 " + number.intValue() + " 行";
            }
            if (map.get("where") instanceof List<?> list && !list.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> cond) {
                        parts.add(conditionText(cond));
                    }
                }
                return "满足 " + String.join(" 且 ", parts) + " 的";
            }
        }
        return "全部";
    }

    private String conditionText(Map<?, ?> cond) {
        String column = text(cond.get("column"));
        String op = text(cond.get("op"));
        String value = text(cond.get("value"));
        return switch (op) {
            case "contains" -> column + " 包含 " + value;
            case "not_contains" -> column + " 不含 " + value;
            case "empty" -> column + " 为空";
            case "not_empty" -> column + " 有值";
            case "gt" -> column + " 大于 " + value;
            case "lt" -> column + " 小于 " + value;
            case "gte" -> column + " 不小于 " + value;
            case "lte" -> column + " 不大于 " + value;
            case "ne" -> column + " 不是 " + value;
            default -> column + " = " + value;
        };
    }

    /* ==================== 落盘格式 ==================== */

    public List<Map<String, Object>> revisionsOf(TaskRecord task) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> revision : rawRevisions(task)) {
            list.add(new LinkedHashMap<>(revision));
        }
        return list;
    }

    public Map<String, Object> toMap(ResultRevision revision) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", revision.id);
        item.put("instruction", revision.instruction);
        item.put("summary", revision.summary);
        item.put("mode", revision.mode);
        item.put("created_at", revision.createdAt);
        item.put("changed_rows", revision.changedRows);
        item.put("ops", revision.ops);
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rawRevisions(TaskRecord task) {
        if (task.config == null) {
            return List.of();
        }
        Object raw = task.config.get("edits");
        return raw instanceof List<?> ? (List<Map<String, Object>>) raw : List.of();
    }

    /* ==================== 真正改结果 ==================== */

    /**
     * 把 task.config.edits 里的每一条修改按顺序重放到结果行上。
     * 返回一共改动了多少行，0 表示没有修改记录。
     */
    public int apply(TaskRecord task, ProcessResult result) {
        List<Map<String, Object>> revisions = rawRevisions(task);
        if (revisions.isEmpty() || result.rows.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map<String, Object> revision : revisions) {
            if (!(revision.get("ops") instanceof List<?> ops)) {
                continue;
            }
            int changed = 0;
            for (Object raw : ops) {
                if (raw instanceof Map<?, ?> op) {
                    changed += applyOp(result, op);
                }
            }
            revision.put("changed_rows", changed);
            total += changed;
        }
        if (total > 0) {
            int limit = Math.min(20, result.rows.size());
            result.preview = new ArrayList<>(result.rows.subList(0, limit));
            result.finish();
        }
        return total;
    }

    private int applyOp(ProcessResult result, Map<?, ?> op) {
        String type = text(op.get("type"));
        List<Integer> targets = targetsOf(result, op.get("scope"));
        if (targets.isEmpty()) {
            return 0;
        }
        switch (type) {
            case "set" -> {
                String column = requireColumn(result, op.get("column"));
                Object value = convertValue(result, column, text(op.get("value")));
                for (int index : targets) {
                    result.rows.get(index).put(column, value);
                }
                return targets.size();
            }
            case "math" -> {
                String column = requireColumn(result, op.get("column"));
                double amount = TextUtils.numberOrZero(text(op.get("value")));
                String mathOp = text(op.get("op"));
                int changed = 0;
                for (int index : targets) {
                    Double current = numberOf(result.rows.get(index).get(column));
                    if (current == null) {
                        continue;
                    }
                    double next = switch (mathOp) {
                        case "sub" -> current - amount;
                        case "mul" -> current * amount;
                        case "div" -> amount == 0 ? current : current / amount;
                        default -> current + amount;
                    };
                    result.rows.get(index).put(column, TextUtils.round1(next));
                    changed++;
                }
                return changed;
            }
            case "delete" -> {
                List<Integer> sorted = new ArrayList<>(targets);
                sorted.sort(Comparator.reverseOrder());
                for (int index : sorted) {
                    result.rows.remove(index);
                }
                return sorted.size();
            }
            case "sort" -> {
                String column = requireColumn(result, op.get("column"));
                boolean desc = "desc".equals(text(op.get("order")));
                result.rows.sort(rowComparator(column, desc));
                return result.rows.size();
            }
            case "add_column" -> {
                String column = text(op.get("column"));
                String template = text(op.get("template"));
                if (!result.outputColumns.contains(column)) {
                    result.outputColumns.add(column);
                }
                for (Map<String, Object> row : result.rows) {
                    row.put(column, render(template, row, result.outputColumns));
                }
                return result.rows.size();
            }
            case "drop_column" -> {
                String column = requireColumn(result, op.get("column"));
                result.outputColumns.remove(column);
                for (Map<String, Object> row : result.rows) {
                    row.remove(column);
                }
                return result.rows.size();
            }
            default -> {
                return 0;
            }
        }
    }

    private Comparator<Map<String, Object>> rowComparator(String column, boolean desc) {
        return (left, right) -> {
            Object a = left.get(column);
            Object b = right.get(column);
            Double na = numberOf(a);
            Double nb = numberOf(b);
            int compare;
            boolean aEmpty = TextUtils.isBlank(text(a));
            boolean bEmpty = TextUtils.isBlank(text(b));
            if (aEmpty && bEmpty) {
                compare = 0;
            } else if (aEmpty) {
                return 1;
            } else if (bEmpty) {
                return -1;
            } else if (na != null && nb != null) {
                compare = Double.compare(na, nb);
            } else {
                compare = text(a).compareToIgnoreCase(text(b));
            }
            return desc ? -compare : compare;
        };
    }

    private List<Integer> targetsOf(ProcessResult result, Object scope) {
        List<Integer> indexes = new ArrayList<>();
        if (!(scope instanceof Map<?, ?> map)) {
            for (int i = 0; i < result.rows.size(); i++) {
                indexes.add(i);
            }
            return indexes;
        }
        if (map.get("row") instanceof Number number) {
            int index = number.intValue() - 1;
            if (index < 0 || index >= result.rows.size()) {
                throw new IllegalArgumentException("第 " + number.intValue() + " 行超出结果范围（共 "
                        + result.rows.size() + " 行）");
            }
            indexes.add(index);
            return indexes;
        }
        if (map.get("where") instanceof List<?> conditions && !conditions.isEmpty()) {
            for (int i = 0; i < result.rows.size(); i++) {
                Map<String, Object> row = result.rows.get(i);
                boolean matched = true;
                for (Object item : conditions) {
                    if (!(item instanceof Map<?, ?> cond) || !matches(row, cond)) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    indexes.add(i);
                }
            }
            return indexes;
        }
        for (int i = 0; i < result.rows.size(); i++) {
            indexes.add(i);
        }
        return indexes;
    }

    private boolean matches(Map<String, Object> row, Map<?, ?> cond) {
        String column = text(cond.get("column"));
        if (!row.containsKey(column)) {
            return false;
        }
        String op = text(cond.get("op"));
        String expected = text(cond.get("value"));
        Object raw = row.get(column);
        String actual = text(raw);
        switch (op) {
            case "empty" -> {
                return actual.isBlank();
            }
            case "not_empty" -> {
                return !actual.isBlank();
            }
            case "contains" -> {
                return actual.contains(expected);
            }
            case "not_contains" -> {
                return !actual.contains(expected);
            }
            default -> {
            }
        }
        Double left = numberOf(raw);
        Double right = TextUtils.parseNumber(expected);
        if (left != null && right != null && !"eq".equals(op) && !"ne".equals(op)) {
            return switch (op) {
                case "gt" -> left > right;
                case "lt" -> left < right;
                case "gte" -> left >= right;
                case "lte" -> left <= right;
                default -> false;
            };
        }
        if (left != null && right != null) {
            boolean same = Math.abs(left - right) < 0.0001;
            return "ne".equals(op) != same;
        }
        return "ne".equals(op) ? !actual.equals(expected) : actual.equals(expected);
    }

    /**
     * 改值时的类型：数值列写数字，其它列写文本，保证导出的 Excel 单元格类型不乱。
     */
    private Object convertValue(ProcessResult result, String column, String value) {
        if (TextUtils.isBlank(value)) {
            return "";
        }
        if (!NUMBER_ONLY.matcher(value).matches() || !isNumericColumn(result, column)) {
            return value;
        }
        double number = Double.parseDouble(value);
        return Math.abs(number - Math.rint(number)) < 0.0001 ? (Object) (long) Math.rint(number)
                : (Object) number;
    }

    private boolean isNumericColumn(ProcessResult result, String column) {
        int checked = 0;
        for (Map<String, Object> row : result.rows) {
            String value = text(row.get(column));
            if (value.isBlank()) {
                continue;
            }
            if (numberOf(row.get(column)) == null) {
                return false;
            }
            checked++;
            if (checked >= 20) {
                break;
            }
        }
        return checked > 0;
    }

    private String render(String template, Map<String, Object> row, List<String> columns) {
        if (TextUtils.isBlank(template)) {
            return "";
        }
        String out = template;
        for (String column : columns) {
            if (out.contains("{" + column + "}")) {
                out = out.replace("{" + column + "}", text(row.get(column)));
            }
        }
        return out;
    }

    private String requireColumn(ProcessResult result, Object raw) {
        String column = text(raw);
        if (!result.outputColumns.contains(column)) {
            throw new IllegalArgumentException("结果表里没有「" + column + "」这一列");
        }
        return column;
    }

    /* ==================== 句式小工具 ==================== */

    private Map<String, Object> scopeOf(String text, List<String> columns) {
        Map<String, Object> scope = new LinkedHashMap<>();
        Matcher row = ROW_NO.matcher(text);
        if (row.find()) {
            scope.put("row", Integer.parseInt(row.group(1)));
            return scope;
        }
        List<Map<String, Object>> conditions = conditionsOf(text, columns);
        if (!conditions.isEmpty()) {
            scope.put("where", conditions);
            return scope;
        }
        scope.put("all", true);
        return scope;
    }

    private List<Map<String, Object>> conditionsOf(String text, List<String> columns) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String column : orderedByLength(columns)) {
            int index = text.indexOf(column);
            if (index < 0) {
                continue;
            }
            Matcher m = COND.matcher(text.substring(index + column.length()));
            if (!m.lookingAt()) {
                continue;
            }
            String value = stripQuotes(m.group(2));
            list.add(condition(column, keywordOp(m.group(1)), value));
        }
        return list;
    }

    private String keywordOp(String keyword) {
        return switch (keyword) {
            case "包含", "含有" -> "contains";
            case "不包含" -> "not_contains";
            case "为空", "是空", "没有值" -> "empty";
            case "不为空", "有值" -> "not_empty";
            case "大于", "超过", "高于" -> "gt";
            case "小于", "低于" -> "lt";
            case "不低于", "不少于" -> "gte";
            case ">" -> "gt";
            case "<" -> "lt";
            case "=", "等于", "为", "是" -> "eq";
            default -> "eq";
        };
    }

    private Map<String, Object> condition(String column, String op, String value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("column", column);
        item.put("op", op);
        if (!"empty".equals(op) && !"not_empty".equals(op)) {
            item.put("value", value);
        }
        return item;
    }

    private String columnIn(String text, List<String> columns) {
        String best = null;
        for (String column : columns) {
            if (!TextUtils.isBlank(column) && text.contains(column)
                    && (best == null || column.length() > best.length())) {
                best = column;
            }
        }
        return best;
    }

    /** 动词前离它最近的那一列，就是用户要改的目标列。 */
    private String columnBefore(String text, int limit, List<String> columns) {
        String best = null;
        int bestIndex = -1;
        String prefix = text.substring(0, Math.max(0, limit));
        for (String column : columns) {
            if (TextUtils.isBlank(column)) {
                continue;
            }
            int index = prefix.lastIndexOf(column);
            if (index >= 0 && index > bestIndex) {
                best = column;
                bestIndex = index;
            }
        }
        return best;
    }

    private String templateOf(String text, List<String> columns) {
        Matcher m = Pattern.compile("(?:值为|值等于|等于|为|是)\\s*(.+)$").matcher(text);
        String template = m.find() ? stripQuotes(m.group(1).trim()) : "";
        if (template.isBlank()) {
            return "";
        }
        // 用户写“部门-姓名”时把现有列名自动包成 {列名}，和拼接规则保持一致
        String out = template;
        for (String column : orderedByLength(columns)) {
            out = out.replace(column, "{" + column + "}");
        }
        return out;
    }

    private List<String> orderedByLength(List<String> columns) {
        List<String> sorted = new ArrayList<>(columns);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    private boolean isAll(Object scope) {
        return !(scope instanceof Map<?, ?> map) || Boolean.TRUE.equals(map.get("all"));
    }

    private Map<String, Object> op(String type, Object... pairs) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            item.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return item;
    }

    private Map<String, Object> plain(JsonNode node) {
        Map<String, Object> item = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> item.put(entry.getKey(), valueOf(entry.getValue())));
        return item;
    }

    private Object valueOf(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), valueOf(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(valueOf(item)));
            return list;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private String stripJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private String stripQuotes(String value) {
        return value.replaceAll("^[「『\"'“”]+|[」』\"'“”]+$", "").trim();
    }

    private String describeRows(TaskRecord task, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        List<Map<String, Object>> rows = task.preview == null ? List.of() : task.preview;
        int limit = Math.min(SAMPLE_ROWS, rows.size());
        for (int i = 0; i < limit; i++) {
            sb.append(i + 1).append(" | ");
            Map<String, Object> row = rows.get(i);
            List<String> cells = new ArrayList<>();
            for (String column : columns) {
                cells.add(column + "=" + text(row.get(column)));
            }
            sb.append(String.join(" ; ", cells)).append("\n");
        }
        return sb.isEmpty() ? "（暂无可展示的行）" : sb.toString();
    }

    private Double numberOf(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        return TextUtils.parseNumber(String.valueOf(value));
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double number) {
            return TextUtils.numberText(number);
        }
        return String.valueOf(value).trim();
    }
}
