package com.xingchen.huibiao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.dto.ApiRequests.FileRef;
import com.xingchen.huibiao.model.FileMeta;
import com.xingchen.huibiao.model.IntentResult;
import com.xingchen.huibiao.model.TemplateItem;
import com.xingchen.huibiao.util.TextUtils;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 把一句人话翻译成可执行的配置。规则先跑，识别不出来的部分才问大模型。
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private static final Pattern RANGE_ISO = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})\\s*(?:到|至|~|—|-{1,2}>?)\\s*(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})");
    private static final Pattern RANGE_CN = Pattern.compile(
            "(?:(\\d{4})\\s*年)?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?\\s*(?:到|至|~|—)"
                    + "\\s*(?:(\\d{4})\\s*年)?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");
    private static final Pattern MONTH_CN = Pattern.compile("(?:(\\d{4})\\s*年)?\\s*(\\d{1,2})\\s*月份?");

    private final FileService files;
    private final HeaderCatalog catalog;
    private final SettingsService settings;
    private final TemplateResolver resolver;
    private final ObjectMapper mapper;
    private final ObjectProvider<ChatModel> chatModel;

    public IntentService(FileService files, HeaderCatalog catalog, SettingsService settings,
                         TemplateResolver resolver, ObjectMapper mapper, ObjectProvider<ChatModel> chatModel) {
        this.files = files;
        this.catalog = catalog;
        this.settings = settings;
        this.resolver = resolver;
        this.mapper = mapper;
        this.chatModel = chatModel;
    }

    public IntentResult analyze(String brief, List<FileRef> refs) {
        return analyze(brief, refs, null);
    }

    // templateFileId 有值就按上传的模板决定导出列，否则用内置模板。
    public IntentResult analyze(String brief, List<FileRef> refs, String templateFileId) {
        if (refs == null || refs.isEmpty()) {
            throw new IllegalArgumentException("至少上传一个源表");
        }
        String text = brief == null ? "" : brief.trim();
        List<Loaded> loaded = new ArrayList<>();
        for (FileRef ref : refs) {
            FileMeta meta = files.require(ref.fileId());
            if (ref.role() != null && !ref.role().isBlank() && !"auto".equals(ref.role())) {
                meta.role = ref.role();
            }
            loaded.add(new Loaded(meta, files.readSheet(meta)));
        }

        IntentResult result = new IntentResult();
        String scene = detectScene(text, loaded);
        if (scene == null) {
            throw new IllegalArgumentException("没看懂这批表要做什么。可以在描述里写明“请假结算”或“加班筛选”。");
        }
        result.scene = scene;
        result.sceneName = "leave".equals(scene) ? "假期余量结算" : "加班记录桥接";
        result.confidence = keywordConfidence(text, scene);
        result.targetTemplate = templateFor(scene, templateFileId, result);
        result.joinKeys = "leave".equals(scene) ? List.of("工号") : List.of();
        result.calculation = "leave".equals(scene)
                ? "按工号关联年假余额，先扣年假，超出部分转事假；天数 × 8 小时"
                : "按部门和日期筛选，申请加班转为数值后输出";
        result.parser = "rules";

        for (Loaded item : loaded) {
            result.sourceColumns.add(new IntentResult.SourceGroup(
                    item.meta.fileId, item.meta.name, item.meta.role,
                    item.sheet.headers(), item.meta.sample));
        }

        int year = inferYear(loaded);
        Range range = parseRange(text, year);
        if (range != null) {
            result.startDate = range.start().toString();
            result.endDate = range.end().toString();
            result.filters.add("日期 " + result.startDate + " 至 " + result.endDate);
        }

        String department = detectDepartment(text, loaded);
        if (department != null) {
            result.department = department;
            result.filters.add("部门 = " + department);
        }

        buildNotes(result, loaded, scene);
        buildMapping(result, loaded);
        enhanceWithModel(result, text, loaded, templateFileId);
        spliceDateTimeTargets(result, loaded);
        if (result.filters.isEmpty()) {
            result.filters.add("不过滤，全量输出");
        }
        return result;
    }

    private String detectScene(String text, List<Loaded> loaded) {
        String t = TextUtils.norm(text);
        if (t.contains("请假") || t.contains("年假") || t.contains("缺勤") || t.contains("事假")
                || t.contains("假期") || t.contains("休假") || t.contains("假别")) {
            return "leave";
        }
        if (t.contains("加班") || t.contains("工时")) {
            return "overtime";
        }
        for (Loaded item : loaded) {
            if ("annual_balance".equals(item.meta.role) || "leave_request".equals(item.meta.role)) {
                return "leave";
            }
            if ("overtime".equals(item.meta.role)) {
                return "overtime";
            }
        }
        for (Loaded item : loaded) {
            Map<String, String> suggested = catalog.suggest(item.sheet.headers(), settings.mergedAliases());
            if (suggested.containsKey("annual_left") || suggested.containsKey("leave_type")) {
                return "leave";
            }
            if (suggested.containsKey("overtime_hours")) {
                return "overtime";
            }
        }
        return null;
    }

    private double keywordConfidence(String text, String scene) {
        String t = TextUtils.norm(text);
        if ("leave".equals(scene)) {
            if (t.contains("年假") && (t.contains("抵扣") || t.contains("优先"))) {
                return 0.95;
            }
            return t.contains("请假") || t.contains("缺勤") ? 0.88 : 0.72;
        }
        return t.contains("加班") ? 0.9 : 0.7;
    }

    private TemplateItem templateFor(String scene, String templateFileId, IntentResult result) {
        if (!TextUtils.isBlank(templateFileId)) {
            FileMeta meta = files.find(templateFileId).orElse(null);
            if (meta == null) {
                result.notes.add("上传的目标模板记录已失效，本次改用内置 HR 模板。");
            } else {
                List<String> columns = cleanColumns(files.readSheet(meta).headers());
                if (columns.size() >= 2) {
                    result.notes.add("导出列取自上传的模板「" + meta.name + "」，共 " + columns.size()
                            + " 列，顺序与模板一致。");
                    return new TemplateItem("tpl_uploaded", meta.name, scene, false,
                            "上传的目标模板，导出文件按它的表头生成。", columns);
                }
                result.notes.add("上传的模板只识别到 " + columns.size() + " 列，导出改用内置 HR 模板。");
            }
        }
        return settings.builtIn("leave".equals(scene) ? "tpl_absence" : "tpl_overtime");
    }

    // 模板表头可能有空列或重复列，导出的列必须唯一，否则写文件时会互相覆盖。
    private List<String> cleanColumns(List<String> headers) {
        List<String> list = new ArrayList<>();
        for (String header : headers) {
            String name = TextUtils.trim(header);
            if (!name.isEmpty() && !list.contains(name)) {
                list.add(name);
            }
        }
        return list;
    }

    private Loaded pick(List<Loaded> loaded, String role) {
        for (Loaded item : loaded) {
            if (role.equals(item.meta.role)) {
                return item;
            }
        }
        for (Loaded item : loaded) {
            Map<String, String> suggested = catalog.suggest(item.sheet.headers(), settings.mergedAliases());
            if ("annual_balance".equals(role) && suggested.containsKey("annual_left")) {
                return item;
            }
            if ("leave_request".equals(role) && suggested.containsKey("leave_type")
                    && !suggested.containsKey("annual_left")) {
                return item;
            }
            if ("overtime".equals(role) && suggested.containsKey("overtime_hours")) {
                return item;
            }
        }
        return null;
    }

    private void buildMapping(IntentResult result, List<Loaded> loaded) {
        Loaded main = "leave".equals(result.scene) ? pick(loaded, "leave_request") : pick(loaded, "overtime");
        if (main == null) {
            main = loaded.get(0);
        }
        Map<String, String> sourceFields = catalog.suggest(main.sheet.headers(), settings.mergedAliases());
        List<String> unknown = new ArrayList<>();

        for (String target : result.targetTemplate.columns()) {
            TemplateResolver.Resolved resolved = resolver.resolve(target, result.scene);
            if (resolved == null) {
                unknown.add(target);
                result.mapping.add(new IntentResult.MappingSuggestion(target, false, null, null, 0));
                continue;
            }
            String column = null;
            double confidence = 0;
            switch (resolved.key()) {
                case "emp_no", "name", "department", "position", "overtime_hours" -> {
                    column = sourceFields.get(resolved.key());
                    confidence = column == null ? 0 : 0.96;
                }
                case "document_no" -> {
                    column = sourceFields.get("document_no");
                    confidence = column == null ? 0 : 0.88;
                }
                case "start" -> {
                    column = sourceFields.get("start_date");
                    confidence = column == null ? 0 : 0.92;
                }
                case "end" -> {
                    column = sourceFields.get("end_date");
                    confidence = column == null ? 0 : 0.92;
                }
                case "hours" -> {
                    column = preferredHoursColumn(main.sheet, sourceFields);
                    confidence = column == null ? 0 : 0.86;
                }
                case "leave_type" -> {
                    column = sourceFields.get("leave_type");
                    confidence = column == null ? 0 : 0.9;
                }
                case "date" -> {
                    column = sourceFields.get("start_date");
                    confidence = column == null ? 0 : 0.94;
                }
                case "no_clock" -> confidence = 1.0;
                default -> {
                }
            }
            result.mapping.add(new IntentResult.MappingSuggestion(
                    target, resolved.required(), column == null ? null : main.meta.fileId, column, confidence));
        }

        if (!unknown.isEmpty()) {
            result.notes.add("模板里的「" + String.join("、", unknown)
                    + "」没有对应的数据来源，导出时会是空值；需要的话在映射里手动指定，或者开严格格式校验前先补上。");
            // 一次出现好几个对不上的列，多半是模板和源表场景不一致（比如拿请假模板配加班数据）
            if (unknown.size() >= 2) {
                result.notes.add("当前按" + ("overtime".equals(result.scene) ? "加班" : "请假")
                        + "场景处理，但这些列在源表里没有来源，可能是模板和源表不是同一类数据，"
                        + "建议换成对应场景的 HR 导入模板再试一次。");
            }
        }
    }

    // “合计小时”优先挂源表的“小时”列。有些 OA 导出的小时列是 0，真正有效的是“天”，
    private String preferredHoursColumn(ExcelService.SheetData sheet, Map<String, String> fields) {
        String hours = fields.get("hours");
        String days = fields.get("days");
        if (hours != null) {
            int limit = Math.min(sheet.rows().size(), 100);
            for (int i = 0; i < limit; i++) {
                Double value = TextUtils.parseNumber(sheet.rows().get(i).values().get(hours));
                if (value != null && value > 0) {
                    return hours;
                }
            }
        }
        return days != null ? days : hours;
    }

    private void buildNotes(IntentResult result, List<Loaded> loaded, String scene) {
        result.notes.add("输出 .xls（HR 最低版本）和 .xlsx 备份，原表只读不改。");
        if ("leave".equals(scene)) {
            Loaded leave = pick(loaded, "leave_request");
            Loaded annual = pick(loaded, "annual_balance");
            if (leave != null) {
                Map<String, String> fields = catalog.suggest(leave.sheet.headers(), settings.mergedAliases());
                String hourCol = fields.get("hours");
                if (hourCol != null) {
                    long zero = leave.sheet.rows().stream()
                            .filter(r -> TextUtils.numberOrZero(r.values().get(hourCol)) == 0)
                            .count();
                    if (zero > 0) {
                        result.notes.add("请假单的“小时”列有 " + zero + " 行是 0，结算时按“天 × 8 小时”折算，天也为 0 的才用小时。");
                    }
                }
            }
            if (annual != null) {
                result.notes.add("年假表首行是“2026年休假明细”这类标题，表头识别会自动下移，不要求手工删行。");
                int unmatched = unmatchedCount(leave, annual);
                if (leave != null && unmatched > 0) {
                    result.notes.add("请假单里有 " + unmatched + " 个工号在年假表中找不到，会按 0 年假处理并写入异常日志。");
                }
            } else {
                result.notes.add("没有上传年假余额表，本批全部按事假输出。");
            }
        } else {
            Loaded overtime = pick(loaded, "overtime");
            if (overtime != null) {
                Map<String, String> fields = catalog.suggest(overtime.sheet.headers(), settings.mergedAliases());
                String hourCol = fields.get("overtime_hours");
                if (hourCol != null) {
                    long bad = overtime.sheet.rows().stream()
                            .filter(r -> TextUtils.parseNumber(r.values().get(hourCol)) == null)
                            .count();
                    if (bad > 0) {
                        result.notes.add("源表有 " + bad + " 行“申请加班”为空或不是数字，会单独隔离。");
                    }
                }
                result.notes.add("源表共 " + overtime.sheet.rows().size() + " 行，按条件过滤后再生成导入表。");
            }
        }
    }

    private int unmatchedCount(Loaded leave, Loaded annual) {
        if (leave == null || annual == null) {
            return 0;
        }
        Map<String, String> leaveFields = catalog.suggest(leave.sheet.headers(), settings.mergedAliases());
        Map<String, String> annualFields = catalog.suggest(annual.sheet.headers(), settings.mergedAliases());
        String leaveId = leaveFields.get("emp_no");
        String annualId = annualFields.get("emp_no");
        if (leaveId == null || annualId == null) {
            return 0;
        }
        Set<String> annualIds = new LinkedHashSet<>();
        for (ExcelService.RowData row : annual.sheet.rows()) {
            annualIds.add(TextUtils.norm(row.values().get(annualId)));
        }
        int count = 0;
        for (ExcelService.RowData row : leave.sheet.rows()) {
            String id = TextUtils.norm(row.values().get(leaveId));
            if (!id.isEmpty() && !annualIds.contains(id)) {
                count++;
            }
        }
        return count;
    }

    private int inferYear(List<Loaded> loaded) {
        for (Loaded item : loaded) {
            Map<String, String> fields = catalog.suggest(item.sheet.headers(), settings.mergedAliases());
            String col = fields.get("start_date");
            if (col == null) {
                col = fields.get("end_date");
            }
            if (col == null) {
                continue;
            }
            for (ExcelService.RowData row : item.sheet.rows()) {
                LocalDate date = TextUtils.parseDate(row.values().get(col));
                if (date != null) {
                    return date.getYear();
                }
            }
        }
        return LocalDate.now().getYear();
    }

    private Range parseRange(String text, int defaultYear) {
        Matcher iso = RANGE_ISO.matcher(text);
        if (iso.find()) {
            LocalDate start = TextUtils.parseDate(iso.group(1));
            LocalDate end = TextUtils.parseDate(iso.group(2));
            if (start != null && end != null) {
                return new Range(start, end);
            }
        }
        Matcher cn = RANGE_CN.matcher(text);
        if (cn.find()) {
            int year = cn.group(1) != null ? Integer.parseInt(cn.group(1))
                    : (cn.group(4) != null ? Integer.parseInt(cn.group(4)) : defaultYear);
            int startMonth = Integer.parseInt(cn.group(2));
            int startDay = cn.group(3) == null ? 1 : Integer.parseInt(cn.group(3));
            int endMonth = Integer.parseInt(cn.group(5));
            int endDay = cn.group(6) == null ? YearMonth.of(year, endMonth).lengthOfMonth()
                    : Integer.parseInt(cn.group(6));
            try {
                return new Range(LocalDate.of(year, startMonth, startDay), LocalDate.of(year, endMonth, endDay));
            } catch (Exception ignored) {
                return null;
            }
        }
        Matcher month = MONTH_CN.matcher(text);
        if (month.find()) {
            int year = month.group(1) != null ? Integer.parseInt(month.group(1)) : defaultYear;
            int m = Integer.parseInt(month.group(2));
            if (m >= 1 && m <= 12) {
                YearMonth ym = YearMonth.of(year, m);
                return new Range(ym.atDay(1), ym.atEndOfMonth());
            }
        }
        return null;
    }

    private String detectDepartment(String text, List<Loaded> loaded) {
        String normText = TextUtils.norm(text);
        if (normText.isEmpty()) {
            return null;
        }
        Set<String> departments = new LinkedHashSet<>();
        for (Loaded item : loaded) {
            Map<String, String> fields = catalog.suggest(item.sheet.headers(), settings.mergedAliases());
            String col = fields.get("department");
            if (col == null) {
                continue;
            }
            int limit = Math.min(item.sheet.rows().size(), 5000);
            for (int i = 0; i < limit; i++) {
                String value = item.sheet.rows().get(i).values().get(col);
                if (!TextUtils.isBlank(value)) {
                    departments.add(value.trim());
                }
                if (departments.size() > 80) {
                    break;
                }
            }
        }
        String best = null;
        for (String dept : departments) {
            String d = TextUtils.norm(dept);
            String key = d.replace("车间", "").replace("事业部", "").replace("部门", "").replace("部", "");
            if (normText.contains(d) || (key.length() >= 2 && normText.contains(key))) {
                if (best == null || dept.length() > best.length()) {
                    best = dept;
                }
            }
        }
        return best;
    }

    private void enhanceWithModel(IntentResult result, String text, List<Loaded> loaded, String templateFileId) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return;
        }
        List<String> targets = result.targetTemplate == null ? List.of() : result.targetTemplate.columns();
        String prompt = """
                你是企业表格处理的配置助手。根据任务描述、目标模板列和源表表头，判断场景，
                并把目标模板的每一列匹配到源表的一列。
                只输出 JSON，不要解释，不要输出 Markdown。
                格式：
                {"scene":"leave 或 overtime 或 unknown","confidence":0.0,"note":"一句提醒",
                 "mapping":[{"target":"目标列名","file":"源表文件名","column":"源表列名","confidence":0.0}]}
                场景说明：leave 是请假/年假/缺勤结算，overtime 是加班记录筛选导出。
                匹配要求：
                1. target 必须逐字来自“目标模板列”，不要新增、改写或翻译。
                2. file 必须逐字来自下面的源表文件名，column 必须逐字来自该表表头。
                3. 对不上或没把握的目标列不要输出，不要编造列名。
                4. 不要计算任何数值。
                5. 源表把“日期”和“时刻”拆成两列时：
                   - 目标列是“起始时间/终止时间/开始时间/结束时间”这类带时刻的列，用 expr 把两列拼起来：
                     {"target":"起始时间","file":"源表.xlsx","expr":"{起始日期} + {起始时间}"}
                     `+` 表示“日期 + 时刻”合并成一个标准日期时间（2026-04-27 20:00）；
                   - 目标列只是“日期”（如 加班日期），只匹配带日期的列，不要拼时刻。
                6. 如果某个目标列在源表里没有单列能对应，但能用别的列拼出来，就在这条里用 expr 代替 column：
                   花括号 {列名} 表示源表的列（必须逐字来自该表表头），花括号外的文字原样保留，常量直接写文字。
                   例：{"target":"缺勤类别","file":"源表.xlsx","expr":"加班"}
                   例：{"target":"合计小时","file":"源表.xlsx","expr":"{天}*8"}
                   能用一列直接对应时不要写 expr。用了 expr 的条目不要再写 column。

                任务描述：
                %s

                目标模板列：
                %s

                源表：
                %s
                """.formatted(
                text == null || text.isBlank() ? "（用户没有额外描述）" : text,
                targets.isEmpty() ? "（未提供模板列）" : String.join("、", targets),
                describeSources(loaded));
        try {
            String answer = model.chat(prompt);
            JsonNode node = mapper.readTree(stripJson(answer));
            String scene = node.path("scene").asText("");
            if (("leave".equals(scene) || "overtime".equals(scene)) && !scene.equals(result.scene)) {
                if (result.confidence < 0.8) {
                    String oldScene = result.scene;
                    result.scene = scene;
                    result.sceneName = "leave".equals(scene) ? "假期余量结算" : "加班记录桥接";
                    // 换场景后内置模板要跟着换，上传的模板仍然保留用户的列
                    if (result.targetTemplate == null || result.targetTemplate.builtIn()) {
                        result.targetTemplate = templateFor(scene, templateFileId, result);
                    }
                    if (!oldScene.equals(scene)) {
                        result.mapping.clear();
                        buildMapping(result, loaded);
                    }
                }
            }
            double confidence = node.path("confidence").asDouble(result.confidence);
            if (confidence > 0) {
                result.confidence = Math.max(result.confidence, Math.min(0.99, confidence));
            }
            int applied = mergeModelMapping(result, node, fileIdByLabel(loaded), columnsByFileId(loaded),
                    mappableTargets(result), samplesByFileId(loaded), keyByTarget(result));
            result.notes.add(applied > 0
                    ? "语义模型参与字段匹配：采用了 " + applied + " 条推荐，其余保留规则结果。"
                    : "语义模型没有给出可用的字段映射，本次保留规则匹配结果。");
            String note = node.path("note").asText("");
            if (!note.isBlank()) {
                result.notes.add("模型提示：" + note);
            }
            result.parser = "rules+langchain4j";
        } catch (Exception e) {
            log.warn("LangChain4j 调用失败，退回规则解析：{}", e.getMessage());
            result.notes.add("语义模型没有响应，本次全部按规则解析。");
        }
    }

    // 时间类目标列的拼接：起始时间/终止时间要的是“日期 + 时刻”，而源表往往把日期和时刻拆成两列。
    private void spliceDateTimeTargets(IntentResult result, List<Loaded> loaded) {
        if (result == null || result.targetTemplate == null || result.mapping == null) {
            return;
        }
        int spliced = 0;
        for (IntentResult.MappingSuggestion item : result.mapping) {
            if (item == null || !TextUtils.isBlank(item.expression)
                    || TextUtils.isBlank(item.column) || TextUtils.isBlank(item.fileId)) {
                continue;
            }
            String target = TextUtils.norm(item.target);
            // 只处理要“日期 + 时刻”的目标列（起始时间/终止时间…），时长、小时这类不碰
            if (!target.contains("时间") || target.contains("时长") || target.contains("工时")) {
                continue;
            }
            Loaded source = null;
            for (Loaded candidate : loaded) {
                if (item.fileId.equals(candidate.meta.fileId)) {
                    source = candidate;
                    break;
                }
            }
            if (source == null) {
                continue;
            }
            // 只有当前选中的是“日期列”才需要补时刻；已经指向时刻列的保持不动
            if (!isDateColumn(item.column, source.meta.sample)) {
                continue;
            }
            String timeColumn = findTimeOnlyColumn(source.sheet.headers(), source.meta.sample, item.column);
            if (timeColumn == null) {
                continue;
            }
            item.expression = "{" + item.column + "} + {" + timeColumn + "}";
            item.column = null;
            spliced++;
        }
        if (spliced > 0) {
            result.notes.add("起始/终止时间按“日期 + 时刻”拼接 " + spliced + " 处，避免只输出日期。");
        }
    }

    private static boolean isDateColumn(String column, Map<String, String> samples) {
        if (TextUtils.isBlank(column) || !TextUtils.norm(column).contains("日期")) {
            return false;
        }
        return TextUtils.parseDate(samples == null ? null : samples.get(column)) != null;
    }

    // 找只有时分秒、解析不出日期的时刻列（起始时间/结束时间这种）。
    private static String findTimeOnlyColumn(List<String> headers, Map<String, String> samples, String dateColumn) {
        if (headers == null) {
            return null;
        }
        String dateKey = pairKeyOf(dateColumn);
        String fallback = null;
        for (String column : headers) {
            String name = TextUtils.norm(column);
            if (name.contains("日期") || !(name.contains("时间") || name.contains("时刻"))) {
                continue;
            }
            if (samples != null && TextUtils.parseDate(samples.get(column)) != null) {
                continue;
            }
            if (fallback == null) {
                fallback = column;
            }
            String key = pairKeyOf(column);
            if (dateKey != null && dateKey.equals(key)) {
                return column;
            }
        }
        return fallback;
    }

    // 只有时分秒、解析不出日期的时刻列（起始时间/结束时间这种）。
    private static String findTimeOnlyColumn(List<String> headers, Map<String, String> samples) {
        return findTimeOnlyColumn(headers, samples, null);
    }

    // 起始/开始 → start，终止/结束 → end，用来把日期列和时刻列配成一对。
    private static String pairKeyOf(String column) {
        if (TextUtils.isBlank(column)) {
            return null;
        }
        String name = TextUtils.norm(column);
        if (name.contains("始")) {
            return "start";
        }
        if (name.contains("止") || name.contains("结束")) {
            return "end";
        }
        return null;
    }

    // 给模型看的源表清单：文件名、表头和每列的第一个非空样例，列太多时截断控制 prompt 长度。
    private String describeSources(List<Loaded> loaded) {
        StringBuilder sb = new StringBuilder();
        for (Loaded item : loaded) {
            sb.append("- ").append(item.meta.name).append("\n");
            sb.append("  表头：").append(String.join("、", item.sheet.headers())).append("\n");
            Map<String, String> sample = new LinkedHashMap<>();
            int limit = Math.min(item.sheet.rows().size(), 50);
            for (int i = 0; i < limit && sample.size() < 30; i++) {
                Map<String, String> values = item.sheet.rows().get(i).values();
                for (String column : item.sheet.headers()) {
                    if (sample.size() >= 30) {
                        break;
                    }
                    if (!sample.containsKey(column) && !TextUtils.isBlank(values.get(column))) {
                        String value = TextUtils.trim(values.get(column));
                        sample.put(column, value.length() > 20 ? value.substring(0, 20) : value);
                    }
                }
            }
            if (!sample.isEmpty()) {
                sb.append("  样例：");
                sample.forEach((column, value) -> sb.append(column).append('=').append(value).append("；"));
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private Map<String, String> fileIdByLabel(List<Loaded> loaded) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Loaded item : loaded) {
            map.put(item.meta.name, item.meta.fileId);
            map.putIfAbsent(item.meta.fileId, item.meta.fileId);
        }
        return map;
    }

    private Map<String, List<String>> columnsByFileId(List<Loaded> loaded) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (Loaded item : loaded) {
            map.put(item.meta.fileId, item.sheet.headers());
        }
        return map;
    }

    // 每列第一个非空样例，用来判断模型指的那一列到底带不带日期。
    private Map<String, Map<String, String>> samplesByFileId(List<Loaded> loaded) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        for (Loaded item : loaded) {
            Map<String, String> sample = new LinkedHashMap<>();
            int limit = Math.min(item.sheet.rows().size(), 50);
            for (int i = 0; i < limit && sample.size() < item.sheet.headers().size(); i++) {
                Map<String, String> values = item.sheet.rows().get(i).values();
                for (String column : item.sheet.headers()) {
                    if (!sample.containsKey(column) && !TextUtils.isBlank(values.get(column))) {
                        sample.put(column, TextUtils.trim(values.get(column)));
                    }
                }
            }
            map.put(item.meta.fileId, sample);
        }
        return map;
    }

    // 目标列对应的内部字段，用来判断哪些目标列本质上是日期。
    private Map<String, String> keyByTarget(IntentResult result) {
        Map<String, String> map = new LinkedHashMap<>();
        if (result.targetTemplate == null) {
            return map;
        }
        for (String target : result.targetTemplate.columns()) {
            TemplateResolver.Resolved resolved = resolver.resolve(target, result.scene);
            if (resolved != null) {
                map.put(target, resolved.key());
            }
        }
        return map;
    }

    // 只有能翻译成内部字段、并且确实从源表取值的模板列才允许模型匹配；
    private Map<String, Boolean> mappableTargets(IntentResult result) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        if (result.targetTemplate == null) {
            return map;
        }
        for (String target : result.targetTemplate.columns()) {
            TemplateResolver.Resolved resolved = resolver.resolve(target, result.scene);
            if (resolved == null || "no_clock".equals(resolved.key())) {
                continue;
            }
            map.put(target, resolved.required());
        }
        return map;
    }

    // 把模型返回的映射合并进规则结果：目标列、文件名、源列名都必须能在当前上下文里对上，
    static int mergeModelMapping(IntentResult result, JsonNode root,
                                 Map<String, String> fileIdByLabel,
                                 Map<String, List<String>> columnsByFileId,
                                 Map<String, Boolean> mappableTargets,
                                 Map<String, Map<String, String>> samplesByFileId,
                                 Map<String, String> keyByTarget) {
        if (result == null || result.targetTemplate == null) {
            return 0;
        }
        JsonNode list = root.path("mapping");
        if (!list.isArray()) {
            return 0;
        }
        Map<String, String> targetByNorm = new LinkedHashMap<>();
        for (String target : result.targetTemplate.columns()) {
            targetByNorm.put(TextUtils.norm(target), target);
        }
        Map<String, IntentResult.MappingSuggestion> current = new LinkedHashMap<>();
        for (IntentResult.MappingSuggestion item : result.mapping) {
            current.put(item.target, item);
        }
        int applied = 0;
        int skippedDate = 0;
        int skippedFile = 0;
        int skippedFixed = 0;
        int index = 0;
        for (JsonNode node : list) {
            if (++index > 100) {
                break;
            }
            String target = targetByNorm.get(TextUtils.norm(node.path("target").asText("")));
            if (target == null) {
                continue;
            }
            // 固定值列（免打卡这类）由系统写死，不接受模型给的常量或表达式：
            // 模型曾在免打卡上给过「否」「请假」，会覆盖规则里正确的「是」
            if (!mappableTargets.containsKey(target)) {
                skippedFixed++;
                continue;
            }
            // 模型偶尔会把文件名写成不带扩展名、带路径或多空格的形态，这里做一次宽松匹配，
            // 否则整批推荐会因为“文件名对不上”被全部丢弃，看起来就像 AI 没参与
            String fileId = resolveFileId(fileIdByLabel, node.path("file").asText(""));
            if (fileId == null) {
                skippedFile++;
                continue;
            }
            double confidence = node.path("confidence").asDouble(0.85);
            confidence = Math.max(0.55, Math.min(0.99, confidence));
            if (confidence < 0.6) {
                continue;
            }
            // 拼接/常量表达式：花括号里必须是该源表真实存在的列，用上了就不再要求单列
            String expr = TextUtils.trim(node.path("expr").asText(""));
            boolean required = mappableTargets.getOrDefault(target, false);
            if (expr != null && ColumnExpressions.isValid(expr, new LinkedHashSet<>(columnsByFileId.getOrDefault(fileId, List.of())))) {
                IntentResult.MappingSuggestion existingExpr = current.get(target);
                if (existingExpr == null) {
                    existingExpr = new IntentResult.MappingSuggestion(target, required, fileId, null, confidence, "ai");
                    existingExpr.expression = expr;
                    result.mapping.add(existingExpr);
                    current.put(target, existingExpr);
                } else {
                    existingExpr.required = required;
                    existingExpr.fileId = fileId;
                    existingExpr.column = null;
                    existingExpr.expression = expr;
                    existingExpr.confidence = confidence;
                    existingExpr.origin = "ai";
                }
                applied++;
                continue;
            }
            String column = matchColumn(columnsByFileId.get(fileId), TextUtils.trim(node.path("column").asText("")));
            if (column == null) {
                continue;
            }
            // 目标列要的是日期，模型却指到只有时分秒的列，且源表另有日期列：忽略这条，保留规则匹配的日期列
            if (isDateTarget(keyByTarget.get(target))
                    && looksTimeOnly(column, samplesByFileId == null ? null : samplesByFileId.get(fileId))
                    && hasDateColumn(columnsByFileId.get(fileId))) {
                skippedDate++;
                continue;
            }
            IntentResult.MappingSuggestion existing = current.get(target);
            if (existing == null) {
                existing = new IntentResult.MappingSuggestion(target, required, fileId, column, confidence, "ai");
                existing.expression = null;
                result.mapping.add(existing);
                current.put(target, existing);
            } else {
                existing.required = required;
                existing.fileId = fileId;
                existing.column = column;
                existing.expression = null;
                existing.confidence = confidence;
                existing.origin = "ai";
            }
            applied++;
        }
        if (skippedDate > 0) {
            result.notes.add("模型有 " + skippedDate + " 条日期映射指向了只有时分秒的列，已忽略，继续用规则匹配的日期列。");
        }
        if (applied == 0 && skippedFile > 0) {
            result.notes.add("模型有 " + skippedFile + " 条映射引用了不存在的源表文件名，已忽略。");
        }
        if (skippedFixed > 0) {
            result.notes.add("模型对固定值列（如免打卡）给了常量，已忽略，继续用系统内置的固定值。");
        }
        return applied;
    }

    // 模型给的文件名允许写成去掉扩展名、带目录、全角或空格的形态，逐个宽松比对一次。
    private static String resolveFileId(Map<String, String> fileIdByLabel, String raw) {
        String label = TextUtils.trim(raw);
        if (label == null || label.isEmpty()) {
            return null;
        }
        String direct = fileIdByLabel.get(label);
        if (direct != null) {
            return direct;
        }
        String norm = stripExt(TextUtils.norm(label));
        if (norm.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : fileIdByLabel.entrySet()) {
            String key = stripExt(TextUtils.norm(entry.getKey()));
            if (key.isEmpty()) {
                continue;
            }
            if (key.equals(norm)) {
                return entry.getValue();
            }
        }
        if (norm.length() < 3) {
            return null;
        }
        for (Map.Entry<String, String> entry : fileIdByLabel.entrySet()) {
            String key = stripExt(TextUtils.norm(entry.getKey()));
            if (!key.isEmpty() && (key.contains(norm) || norm.contains(key))) {
                return entry.getValue();
            }
        }
        return null;
    }

    // TextUtils.norm 会删掉点号，扩展名只能按后缀剥：oa导出请假单xlsx → oa导出请假单。
    private static String stripExt(String norm) {
        return norm == null ? "" : norm.replaceAll("(xlsx|xlsm|xls|csv)$", "");
    }

    private static boolean isDateTarget(String key) {
        return "start".equals(key) || "end".equals(key) || "date".equals(key);
    }

    // 表头写着“时间/时刻”、样例里又解析不出日期，才算只有时分秒的列。
    private static boolean looksTimeOnly(String column, Map<String, String> samples) {
        String name = TextUtils.norm(column);
        if (name.contains("日期") || name.contains("date")) {
            return false;
        }
        if (!(name.contains("时间") || name.contains("时刻") || name.contains("time"))) {
            return false;
        }
        String sample = samples == null ? null : samples.get(column);
        return TextUtils.parseDate(sample) == null;
    }

    private static boolean hasDateColumn(List<String> headers) {
        if (headers == null) {
            return false;
        }
        return headers.stream().anyMatch(header -> TextUtils.norm(header).contains("日期"));
    }

    private static String matchColumn(List<String> headers, String raw) {
        if (headers == null || raw == null || raw.isBlank()) {
            return null;
        }
        for (String header : headers) {
            if (header.equals(raw)) {
                return header;
            }
        }
        String norm = TextUtils.norm(raw);
        for (String header : headers) {
            if (TextUtils.norm(header).equals(norm)) {
                return header;
            }
        }
        return null;
    }

    private String stripJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private record Loaded(FileMeta meta, ExcelService.SheetData sheet) {
    }

    private record Range(LocalDate start, LocalDate end) {
    }
}
