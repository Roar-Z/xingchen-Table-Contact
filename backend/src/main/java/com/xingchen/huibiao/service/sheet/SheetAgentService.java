package com.xingchen.huibiao.service.sheet;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 表格智作的语义层：把一句话翻译成结构化操作。
@Service
public class SheetAgentService {

    private static final Logger log = LoggerFactory.getLogger(SheetAgentService.class);
    private static final Set<String> OPS = Set.of(
            "set", "math", "delete", "sort", "add_column", "drop_column");

    private static final Pattern ROW_NO = Pattern.compile("第\\s*(\\d{1,5})\\s*行");
    private static final Pattern SET_VERB = Pattern.compile("改成|改为|设成|设为|变成|换成|填成|填为|写成|改到|修改为|调整为|更新为");
    private static final Pattern MATH = Pattern.compile("(加上|加|减去|减|乘以|乘|除以|除)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern CONDITION = Pattern.compile("(?:满足|其中|所有|把|将)?\\s*([^，,。；;\\s]{1,14}?)\\s*(?:为|是|等于|=)\\s*([^，,。；;\\s]{0,30}?)");
    private static final Pattern SORT = Pattern.compile("(?:按|根据|依照)\\s*([^，,。；;\\s]{1,14}?)\\s*(升序|降序|由小到大|由大到小|从低到高|从高到低|正序|倒序|排序)");
    private static final Pattern ADD_COLUMN = Pattern.compile("(?:新增|增加|添加|加)\\s*(?:一列|1列|列)?\\s*[「“\"']?([^「」“”\"'，,。；;\\s]{1,14})");
    private static final Pattern DROP_COLUMN = Pattern.compile("(?:删除|删掉|去掉|移除)\\s*(?:该列|这一列|一列|列)?\\s*[「“\"']?([^「」“”\"'，,。；;\\s]{1,14})");
    private static final Pattern COLUMN_LIST = Pattern.compile("(?:列|字段|表头|包含|包括|要有|需要有|分别是)\\s*[：:是]?\\s*([^。；;]{2,120})");

    private final ObjectProvider<ChatModel> models;
    private final ObjectMapper mapper;

    public SheetAgentService(ObjectProvider<ChatModel> models, ObjectMapper mapper) {
        this.models = models;
        this.mapper = mapper;
    }

    // ==================== 改表 ====================

    public Map<String, Object> parseEdit(String instruction, List<String> columns, List<Map<String, Object>> sample) {
        ChatModel model = models.getIfAvailable();
        if (model != null) {
            try {
                Parsed parsed = parseEditByModel(model, instruction, columns, sample);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("summary", parsed.summary.isBlank() ? instruction.trim() : parsed.summary);
                out.put("mode", "llm");
                out.put("ops", parsed.ops);
                return out;
            } catch (Exception e) {
                log.warn("表格语义解析失败，改用句式兜底：{}", e.getMessage());
            }
        }
        List<Map<String, Object>> ops = parseEditByRules(instruction, columns);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", instruction.trim());
        out.put("mode", "rules");
        out.put("ops", ops);
        return out;
    }

    private record Parsed(String summary, List<Map<String, Object>> ops) {
    }

    private Parsed parseEditByModel(ChatModel model, String instruction,
                                    List<String> columns, List<Map<String, Object>> sample) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是表格编辑助手，负责把用户的一句中文改表要求翻译成操作 JSON。\n");
        prompt.append("当前表列名（必须逐字使用，不能编新的）：").append(String.join("、", columns)).append("\n");
        if (sample != null && !sample.isEmpty()) {
            prompt.append("前几行数据：").append(toJson(sample)).append("\n");
        }
        prompt.append("\n支持的操作（op 字段）：\n");
        prompt.append("1 set：把某列改成某个值。{\"op\":\"set\",\"column\":\"列名\",\"value\":\"新值\",\"scope\":SCOPE}\n");
        prompt.append("2 math：对数值列加减乘除。{\"op\":\"math\",\"column\":\"列名\",\"operator\":\"+\",\"value\":1,\"scope\":SCOPE}\n");
        prompt.append("3 delete：删掉符合条件的行。{\"op\":\"delete\",\"scope\":SCOPE}。scope 不能是 {\"type\":\"all\"}\n");
        prompt.append("4 sort：整表排序。{\"op\":\"sort\",\"column\":\"列名\",\"order\":\"asc|desc\"}\n");
        prompt.append("5 add_column：新增一列。{\"op\":\"add_column\",\"column\":\"列名\",\"value\":\"每行默认值\"}\n");
        prompt.append("6 drop_column：删掉一列。{\"op\":\"drop_column\",\"column\":\"列名\"}\n");
        prompt.append("\nSCOPE 只有三种：\n");
        prompt.append("{\"type\":\"all\"} 全部行\n");
        prompt.append("{\"type\":\"row\",\"row\":3} 第 3 行（从 1 开始，按当前表顺序）\n");
        prompt.append("{\"type\":\"where\",\"conditions\":[{\"column\":\"列名\",\"equals\":\"值\"}]} 条件行；");
        prompt.append("空值用 {\"column\":\"列名\",\"empty\":true}；模糊匹配用 contains\n");
        prompt.append("\n要求：\n");
        prompt.append("- 只输出 JSON，不要解释，不要 Markdown 代码块\n");
        prompt.append("- 用户说“这列/那一列”时结合上下文指到具体列名\n");
        prompt.append("- 一句话里有多件事就输出多个 ops，按执行顺序排列\n");
        prompt.append("- 听不出可执行的操作时输出 {\"summary\":\"...\",\"ops\":[]}\n");
        prompt.append("\n用户的话：").append(instruction).append("\n");
        prompt.append("输出格式：{\"summary\":\"一句话说明改了什么\",\"ops\":[...]}\n");

        String answer = model.chat(prompt.toString());
        Map<String, Object> parsed = readJsonObject(answer);
        Object opsNode = parsed.get("ops");
        List<Map<String, Object>> ops = new ArrayList<>();
        if (opsNode instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    ops.add(toStringKeyMap(map));
                }
            }
        }
        normalize(ops, columns);
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("模型没有给出可执行的操作");
        }
        String summary = parsed.get("summary") == null ? "" : String.valueOf(parsed.get("summary"));
        return new Parsed(summary, ops);
    }

    private List<Map<String, Object>> parseEditByRules(String instruction, List<String> columns) {
        String text = halfWidth(instruction).replace(" ", "");
        List<Map<String, Object>> ops = new ArrayList<>();
        Integer rowNo = rowNumberOf(text);

        Matcher sort = SORT.matcher(text);
        if (sort.find()) {
            List<String> hits = matchColumns(columns, sort.group(1));
            if (!hits.isEmpty()) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "sort");
                op.put("column", hits.get(0));
                op.put("order", isDesc(sort.group(2)) ? "desc" : "asc");
                ops.add(op);
                return ops;
            }
        }

        boolean wantsDelete = text.matches("(?s).*(删除|删掉|去掉|移除|剔除).*");
        if (wantsDelete) {
            Matcher cond = CONDITION.matcher(text);
            if (cond.find() && text.matches("(?s).*(行|记录|条).*")) {
                List<String> cols = matchColumns(columns, cond.group(1));
                if (!cols.isEmpty()) {
                    String value = clean(cond.group(2));
                    Map<String, Object> scope = new LinkedHashMap<>();
                    scope.put("type", "where");
                    Map<String, Object> condition = new LinkedHashMap<>();
                    condition.put("column", cols.get(0));
                    if (value.isEmpty()) {
                        condition.put("empty", true);
                    } else {
                        condition.put("equals", value);
                    }
                    scope.put("conditions", List.of(condition));
                    Map<String, Object> op = new LinkedHashMap<>();
                    op.put("op", "delete");
                    op.put("scope", scope);
                    ops.add(op);
                    return ops;
                }
            }
            if (rowNo != null) {
                Map<String, Object> scope = new LinkedHashMap<>();
                scope.put("type", "row");
                scope.put("row", rowNo);
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "delete");
                op.put("scope", scope);
                ops.add(op);
                return ops;
            }
            if (text.matches("(?s).*列.*")) {
                Matcher drop = DROP_COLUMN.matcher(text);
                if (drop.find()) {
                    List<String> cols = matchColumns(columns, drop.group(1));
                    if (!cols.isEmpty()) {
                        Map<String, Object> op = new LinkedHashMap<>();
                        op.put("op", "drop_column");
                        op.put("column", cols.get(0));
                        ops.add(op);
                        return ops;
                    }
                }
            }
        }

        if (text.matches("(?s).*(新增|增加|添加|加一列|加列).*")) {
            Matcher add = ADD_COLUMN.matcher(text);
            if (add.find()) {
                List<String> cols = matchColumns(columns, add.group(1));
                if (cols.isEmpty()) {
                    Map<String, Object> op = new LinkedHashMap<>();
                    op.put("op", "add_column");
                    op.put("column", clean(add.group(1)));
                    op.put("value", "");
                    ops.add(op);
                    return ops;
                }
            }
        }

        Matcher math = MATH.matcher(text);
        if (math.find()) {
            String head = text.substring(0, math.start());
            List<String> cols = matchColumns(columns, head);
            if (!cols.isEmpty()) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "math");
                op.put("column", cols.get(0));
                op.put("operator", operatorOf(math.group(1)));
                op.put("value", Double.parseDouble(math.group(2)));
                op.put("scope", rowNo == null ? scopeAll() : scopeRow(rowNo));
                ops.add(op);
                return ops;
            }
        }

        Matcher set = SET_VERB.matcher(text);
        if (set.find()) {
            String head = text.substring(0, set.start());
            String value = clean(text.substring(set.end()));
            buildSet(head, value, rowNo, columns).ifPresent(ops::add);
            if (!ops.isEmpty()) {
                return ops;
            }
        }

        if (ops.isEmpty()) {
            throw new IllegalArgumentException("这句话没听出要改什么，可以说「第 3 行的姓名改成张三」或「按数量降序排序」");
        }
        return ops;
    }

    private java.util.Optional<Map<String, Object>> buildSet(String head, String value, Integer rowNo, List<String> columns) {
        String cleanHead = head.replaceAll("^(把|将|请|帮我|麻烦)?", "").replace("的", "");
        Matcher cond = CONDITION.matcher(head);
        if (cond.find()) {
            List<String> condCols = matchColumns(columns, cond.group(1));
            if (!condCols.isEmpty()) {
                String condValue = clean(cond.group(2));
                String rest = head.substring(cond.end());
                List<String> targets = matchColumns(columns, rest);
                if (targets.isEmpty()) {
                    return java.util.Optional.empty();
                }
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put("column", condCols.get(0));
                if (condValue.isEmpty()) {
                    condition.put("empty", true);
                } else {
                    condition.put("equals", condValue);
                }
                Map<String, Object> scope = new LinkedHashMap<>();
                scope.put("type", "where");
                scope.put("conditions", List.of(condition));
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "set");
                op.put("scope", scope);
                op.put("column", targets.get(0));
                op.put("value", value);
                return java.util.Optional.of(op);
            }
        }
        List<String> targets = matchColumns(columns, cleanHead);
        if (targets.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "set");
        op.put("scope", rowNo == null ? scopeAll() : scopeRow(rowNo));
        op.put("column", targets.get(0));
        op.put("value", value);
        return java.util.Optional.of(op);
    }

    // ==================== 建表 / 融合 ====================

    public Map<String, Object> compose(String instruction, List<Map<String, Object>> sheets) {
        ChatModel model = models.getIfAvailable();
        if (model != null) {
            try {
                return composeByModel(model, instruction, sheets);
            } catch (Exception e) {
                log.warn("建表意图解析失败，改用句式兜底：{}", e.getMessage());
            }
        }
        return composeByRules(instruction, sheets);
    }

    private Map<String, Object> composeByModel(ChatModel model, String instruction, List<Map<String, Object>> sheets) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是表格助手，负责根据用户的话新建表格，或把已有的几张表合成一张。\n");
        if (sheets == null || sheets.isEmpty()) {
            prompt.append("当前没有已上传的表。\n");
        } else {
            prompt.append("已有表：\n");
            for (Map<String, Object> sheet : sheets) {
                prompt.append("- ").append(sheet.get("name")).append("，列：")
                        .append(sheet.get("columns")).append("，行数：").append(sheet.get("row_count")).append("\n");
                if (sheet.get("sample") != null) {
                    prompt.append("  样例：").append(toJson(sheet.get("sample"))).append("\n");
                }
            }
        }
        prompt.append("\n用户的话：").append(instruction).append("\n\n");
        prompt.append("只输出 JSON，不要解释，不要代码块。两种形态二选一：\n");
        prompt.append("新建：{\"mode\":\"create\",\"name\":\"表名\",\"columns\":[\"列1\",\"列2\"],");
        prompt.append("\"sample_rows\":[[\"示例1\",\"示例2\"]],\"summary\":\"一句话说明\"}\n");
        prompt.append("融合：{\"mode\":\"merge\",\"name\":\"表名\",\"how\":\"append|join\",\"key\":\"用于关联的列名\",");
        prompt.append("\"columns\":[\"最终列1\",\"最终列2\"],\"summary\":\"一句话说明\"}\n");
        prompt.append("规则：\n");
        prompt.append("- 列名用简短中文，用户点名了列就按用户说的顺序\n");
        prompt.append("- append 是纵向合并（列结构相同，行叠加）；join 是按 key 列横向关联（例如按工号）\n");
        prompt.append("- 只有一个表时不要用 merge\n");
        prompt.append("- 用户只是要改现有表时，返回 {\"mode\":\"edit\",\"target\":\"表名\",\"summary\":\"...\"}\n");

        String answer = model.chat(prompt.toString());
        Map<String, Object> plan = readJsonObject(answer);
        String mode = String.valueOf(plan.getOrDefault("mode", "create"));
        if (!List.of("create", "merge", "edit").contains(mode)) {
            throw new IllegalArgumentException("模型返回的模式不认识");
        }
        plan.put("mode", mode);
        return plan;
    }

    private Map<String, Object> composeByRules(String instruction, List<Map<String, Object>> sheets) {
        String text = instruction == null ? "" : instruction.trim();
        Map<String, Object> plan = new LinkedHashMap<>();
        if (sheets != null && sheets.size() >= 2 && text.matches("(?s).*(合并|融合|关联|拼接|汇总到一张|合成一张|按工号|按编号).*")) {
            plan.put("mode", "merge");
            plan.put("name", titleFrom(text, "合并结果"));
            List<String> key = commonKey(sheets);
            plan.put("how", key.isEmpty() ? "append" : "join");
            plan.put("key", key.isEmpty() ? "" : key.get(0));
            plan.put("summary", key.isEmpty() ? "把几张表纵向合并" : "按「" + key.get(0) + "」关联几张表");
            return plan;
        }

        Map<String, Object> template = SheetTemplates.guess(text);
        List<String> columns = explicitColumns(text);
        if (columns.isEmpty() && template != null) {
            columns = castList(template.get("columns"));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("没听出要哪些列，可以说「新建一张表，列：工号、姓名、部门」");
        }
        plan.put("mode", "create");
        plan.put("name", titleFrom(text, template == null ? "新建表格" : String.valueOf(template.get("name"))));
        plan.put("columns", columns);
        plan.put("sample_rows", List.of());
        plan.put("summary", "按你说的列建一张表");
        return plan;
    }

    private List<String> explicitColumns(String text) {
        Matcher matcher = COLUMN_LIST.matcher(text);
        if (!matcher.find()) {
            return List.of();
        }
        String body = matcher.group(1);
        String[] parts = body.split("[、,，/|和及\\s]+");
        List<String> columns = new ArrayList<>();
        for (String part : parts) {
            String name = clean(part);
            // 「列：a、b、c，先放两行示例数据」里，最后那半句是在说样例，不是列名
            if (name.matches(".*(示例|样例|示范|先放|再放|然后).*")) {
                continue;
            }
            if (name.length() >= 1 && name.length() <= 14 && !name.matches(".*[的了].*")) {
                columns.add(name);
            }
        }
        return columns.size() >= 2 ? columns : List.of();
    }

    private List<String> commonKey(List<Map<String, Object>> sheets) {
        List<String> candidates = List.of("工号", "编号", "单号", "姓名", "项目", "ID");
        for (String key : candidates) {
            boolean all = true;
            for (Map<String, Object> sheet : sheets) {
                List<String> columns = castList(sheet.get("columns"));
                if (!columns.contains(key)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return List.of(key);
            }
        }
        return List.of();
    }

    private String titleFrom(String text, String fallback) {
        Matcher matcher = Pattern.compile("(?:叫|命名为|名字是|名称为|名字叫)\\s*[「“\"']?([^「」“”\"'，,。；;\\s]{2,20})").matcher(text);
        if (matcher.find()) {
            return clean(matcher.group(1));
        }
        Matcher make = Pattern.compile("(?:新建|建|创建|做|生成)(?:一张|个)?\\s*[「“\"']?([^「」“”\"'，,。；;\\s]{0,14}?)(?:表|表格)").matcher(text);
        if (make.find()) {
            String name = clean(make.group(1));
            if (!name.isEmpty() && !name.matches("^(一张|个|新的|个新的)$")) {
                return name;
            }
        }
        return fallback;
    }

    // ==================== 通用 ====================

    private void normalize(List<Map<String, Object>> ops, List<String> columns) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> op : ops) {
            String type = String.valueOf(op.get("op"));
            if (!OPS.contains(type)) {
                continue;
            }
            if (List.of("set", "math", "sort", "drop_column").contains(type)) {
                List<String> hits = matchColumns(columns, String.valueOf(op.get("column")));
                if (hits.isEmpty()) {
                    throw new IllegalArgumentException("表里没有「" + op.get("column") + "」这一列");
                }
                op.put("column", hits.get(0));
            }
            if ("delete".equals(type)) {
                Object scope = op.get("scope");
                if (!(scope instanceof Map<?, ?> map) || "all".equals(String.valueOf(map.get("type")))) {
                    continue;
                }
            }
            kept.add(op);
        }
        ops.clear();
        ops.addAll(kept);
    }

    public static List<String> matchColumns(List<String> columns, String hint) {
        if (hint == null) {
            return List.of();
        }
        String text = clean(hint);
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> exact = new ArrayList<>();
        for (String column : columns) {
            if (column.equals(text)) {
                return List.of(column);
            }
        }
        for (String column : columns) {
            String normColumn = column.replace(" ", "");
            if (text.contains(normColumn) || normColumn.contains(text)) {
                exact.add(column);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        // 最后一个办法：找列名里出现过的片段，比如“合计时间”对“合计小时”不算，但对“处理时间”算
        for (String column : columns) {
            String normColumn = column.replace(" ", "");
            for (int i = 0; i + 2 <= text.length(); i++) {
                String piece = text.substring(i, i + 2);
                if (normColumn.contains(piece) && piece.length() >= 2 && !piece.matches("[的了把将该这那].")) {
                    exact.add(column);
                    break;
                }
            }
        }
        return exact;
    }

    private Map<String, Object> readJsonObject(String answer) {
        if (answer == null) {
            throw new IllegalArgumentException("模型没有返回内容");
        }
        String text = answer.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型没有返回 JSON");
        }
        try {
            return mapper.readValue(text.substring(start, end + 1), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("模型返回的 JSON 解析失败：" + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private Integer rowNumberOf(String text) {
        Matcher matcher = ROW_NO.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Map<String, Object> scopeAll() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", "all");
        return scope;
    }

    private Map<String, Object> scopeRow(int row) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", "row");
        scope.put("row", row);
        return scope;
    }

    private boolean isDesc(String word) {
        return word != null && (word.contains("降") || word.contains("大到小") || word.contains("高到低") || word.contains("倒"));
    }

    private String operatorOf(String verb) {
        if (verb.startsWith("减")) {
            return "-";
        }
        if (verb.startsWith("乘")) {
            return "*";
        }
        if (verb.startsWith("除")) {
            return "/";
        }
        return "+";
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^[「“\"'\\s]+|[」”\"'\\s。！!？?]+$", "").trim();
    }

    private String halfWidth(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
