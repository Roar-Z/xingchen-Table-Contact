package com.xingchen.huibiao.service.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.service.ExcelService;
import com.xingchen.huibiao.service.HeaderCatalog;
import com.xingchen.huibiao.service.SettingsService;
import com.xingchen.huibiao.util.TextUtils;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体模式：上传一个 zip / 一整个文件夹，只说业务目标，剩下的交给它。
 *
 * 七个原子能力各自独立：扫描数据集、解析表格、识别业务表、字段语义对齐、跨表关联计算、
 * 异常隔离、标准化导出。规划阶段先把前六步跑出来交给用户核对，确认后才真正执行导出。
 * 计算、筛选、导出都在 Java 里做，模型只负责“选哪张表、哪两列是一回事”。
 */
@Service
public class BridgeAgentService {

    private static final Logger log = LoggerFactory.getLogger(BridgeAgentService.class);

    /** 标准字段：跨系统的统一叫法，对齐的目标 */
    private record Std(String key, String label, List<String> aliases) {
    }

    private static final List<Std> STD = List.of(
            new Std("emp_no", "工号", List.of("工号", "员工编号", "人员编号", "员工号", "职员编号", "人员代码",
                    "empno", "emp_no", "employeeid", "employee_id")),
            new Std("name", "姓名", List.of("姓名", "员工姓名", "人员姓名", "职员名称", "职员姓名", "申请人",
                    "员工", "name")),
            new Std("department", "部门", List.of("部门", "部门名称", "所属部门", "组织", "班组", "车间")),
            new Std("position", "职位", List.of("职位", "岗位", "职务", "职位名称", "岗位名称")),
            new Std("onboard_date", "入职日期", List.of("入职日期", "入职时间", "到岗日期", "录用日期", "进厂日期",
                    "聘用日期", "入职年月")),
            new Std("status", "在职状态", List.of("在职状态", "员工状态", "人员状态", "在职")),
            new Std("base_salary", "基本工资", List.of("基本工资", "基本薪资", "底薪", "岗位工资", "月薪", "标准工资")),
            new Std("performance", "绩效", List.of("绩效", "绩效工资", "绩效奖金", "考核工资")),
            new Std("allowance", "补贴", List.of("补贴", "津贴", "补助", "餐补", "交通补贴")),
            new Std("deduction", "扣款", List.of("扣款", "代扣", "个税", "社保", "公积金", "扣减")),
            new Std("gross_salary", "应发工资", List.of("应发工资", "应发合计", "应付工资", "应发")),
            new Std("net_salary", "实发工资", List.of("实发工资", "实发合计", "到手工资", "实发")),
            new Std("overtime_hours", "加班小时", List.of("加班小时", "加班时长", "申请加班", "加班时数", "overtime")),
            new Std("leave_type", "请假类型", List.of("请假类型", "假别", "缺勤类别", "假期类型")),
            new Std("start_date", "开始日期", List.of("开始日期", "起始日期", "请假开始日期", "加班日期")),
            new Std("end_date", "结束日期", List.of("结束日期", "截止日期", "终止日期", "请假结束日期")),
            new Std("days", "天数", List.of("天数", "天", "请假天数", "合计天数", "休假天数")),
            new Std("hours", "请假小时", List.of("请假小时", "小时", "合计小时")),
            new Std("annual_left", "年假余额", List.of("年假余额", "剩余年假", "可用年假")),
            new Std("amount", "金额", List.of("金额", "合计金额", "费用"))
    );

    /** 这些标准字段是数值，聚合时按工号累加 */
    private static final Set<String> STD_NUMERIC = Set.of(
            "base_salary", "performance", "allowance", "deduction", "gross_salary", "net_salary",
            "overtime_hours", "days", "hours", "annual_left", "amount");

    /** 主数据类型：一人一行，缺行代表信息缺失，需要进异常清单 */
    private static final Set<String> MASTER_KINDS = Set.of("hr_employee", "erp_salary", "hr_annual");

    /** 异常清单的行数上限：数据集再大也不至于写出一张几万行的清单 */
    private static final int ANOMALY_LIMIT = 5000;

    private static final Map<String, String> KIND_LABELS = Map.of(
            "hr_employee", "HR 员工表",
            "erp_salary", "ERP 薪资表",
            "oa_leave", "OA 请假单",
            "oa_overtime", "OA 加班表",
            "hr_annual", "HR 年假表",
            "unknown", "普通表"
    );

    private static final Map<String, List<String>> KIND_KEYWORDS = new LinkedHashMap<>();

    static {
        KIND_KEYWORDS.put("hr_employee", List.of("入职", "员工", "花名册", "在职", "转正"));
        KIND_KEYWORDS.put("erp_salary", List.of("工资", "薪资", "薪酬", "基本工资", "应发", "实发", "绩效", "个税"));
        KIND_KEYWORDS.put("oa_leave", List.of("请假", "缺勤", "假别", "休假"));
        KIND_KEYWORDS.put("oa_overtime", List.of("加班", "overtime"));
        KIND_KEYWORDS.put("hr_annual", List.of("年假", "年休假", "调休"));
    }

    private static final Pattern RANGE_ISO = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})\\s*(?:到|至|~|—|-)\\s*(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})");
    private static final Pattern RANGE_CN = Pattern.compile(
            "(?:(\\d{4})\\s*年)?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})?\\s*日?\\s*(?:到|至|~|—)"
                    + "\\s*(?:(\\d{4})\\s*年)?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})?\\s*日?");

    private final BridgeStore store;
    private final ExcelService excel;
    private final HeaderCatalog catalog;
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final ObjectProvider<ChatModel> chat;

    public BridgeAgentService(BridgeStore store, ExcelService excel, HeaderCatalog catalog,
                              SettingsService settings, ObjectMapper mapper, ObjectProvider<ChatModel> chat) {
        this.store = store;
        this.excel = excel;
        this.catalog = catalog;
        this.settings = settings;
        this.mapper = mapper;
        this.chat = chat;
    }

    /* ==================== 1. 上传 + 扫描 ==================== */

    public BridgeDataset create(String name, String sourceType, List<MultipartFile> uploads) {
        BridgeDataset dataset = new BridgeDataset();
        dataset.id = store.newId();
        dataset.name = name == null || name.isBlank() ? "数据集 " + dataset.id : name.trim();
        dataset.sourceType = sourceType == null || sourceType.isBlank() ? "files" : sourceType;
        dataset.createdAt = store.now();

        int saved = store.materialize(dataset.id, uploads);
        if (saved <= 0) {
            throw new IllegalArgumentException("没有找到可解析的表格，支持 .zip / .xlsx / .xls / .csv");
        }

        int index = 1;
        Map<String, String> seen = new LinkedHashMap<>();
        for (Path file : store.tableFiles(dataset.id)) {
            ExcelService.SheetData sheet;
            try {
                sheet = excel.read(file, catalog, settings.mergedAliases(), true);
            } catch (Exception e) {
                dataset.notes.add("跳过 " + file.getFileName() + "：" + e.getMessage());
                continue;
            }
            if (sheet.headers().isEmpty() || sheet.rows().isEmpty()) {
                dataset.notes.add("跳过 " + sheet.sheetName() + "：没有读到表头或数据行");
                continue;
            }
            // 不同系统常常导出同一份数据（ERP 导出的加班表 vs 导入 HR 的加班表），
            // 两张都参与计算就会翻倍。表头、行数、首尾行一模一样时只保留第一张。
            String signature = signatureOf(sheet);
            if (seen.containsKey(signature)) {
                dataset.notes.add(file.getFileName() + " 和 " + seen.get(signature)
                        + " 数据完全相同，只用 " + seen.get(signature) + "，跳过重复的那份");
                continue;
            }
            seen.put(signature, file.getFileName().toString());
            BridgeDataset.Table table = new BridgeDataset.Table();
            table.id = "T" + index++;
            table.file = file.getFileName().toString();
            table.stored = table.file;
            table.rows = sheet.rows().size();
            table.columns = new ArrayList<>(sheet.headers());
            table.sample = sampleOf(sheet);
            classify(table);
            dataset.tables.add(table);
            if (sheet.sheets().size() > 1) {
                dataset.notes.add(table.file + " 有 " + sheet.sheets().size() + " 个 sheet，本次解析「"
                        + sheet.sheetName() + "」");
            }
        }
        if (dataset.tables.isEmpty()) {
            throw new IllegalArgumentException("压缩包里没有可用的表格数据，请确认里面有 .xlsx / .xls / .csv");
        }
        dataset.notes.add("扫描到 " + dataset.tables.size() + " 张表：" + String.join("、",
                dataset.tables.stream().map(t -> t.file + "（" + t.kindLabel + "）").toList()));
        store.save(dataset);
        return dataset;
    }

    private Map<String, String> sampleOf(ExcelService.SheetData sheet) {
        Map<String, String> sample = new LinkedHashMap<>();
        ExcelService.RowData first = sheet.rows().get(0);
        int limit = 0;
        for (String header : sheet.headers()) {
            if (limit++ >= 8) break;
            sample.put(header, first.values().getOrDefault(header, ""));
        }
        return sample;
    }

    /** 表头 + 行数 + 首尾行，用来判断两份导出其实是同一批数据 */
    private String signatureOf(ExcelService.SheetData sheet) {
        StringBuilder sb = new StringBuilder();
        for (String header : sheet.headers()) sb.append(TextUtils.norm(header)).append('|');
        sb.append('#').append(sheet.rows().size()).append('#');
        ExcelService.RowData first = sheet.rows().get(0);
        ExcelService.RowData last = sheet.rows().get(sheet.rows().size() - 1);
        for (String header : sheet.headers()) {
            sb.append(first.values().getOrDefault(header, "")).append('|');
        }
        for (String header : sheet.headers()) {
            sb.append(last.values().getOrDefault(header, "")).append('|');
        }
        return sb.toString();
    }

    /** 按文件名 + 表头给表安一个业务身份，用词都很俗，够用就行 */
    private void classify(BridgeDataset.Table table) {
        String fileName = TextUtils.norm(table.file);
        String headerText = TextUtils.norm(String.join("", table.columns));
        String bestKind = "unknown";
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : KIND_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                String key = TextUtils.norm(keyword);
                if (fileName.contains(key)) score += 3;
                if (headerText.contains(key)) score += 2;
            }
            if (score > bestScore) {
                bestScore = score;
                bestKind = entry.getKey();
            }
        }
        table.kind = bestScore > 0 ? bestKind : "unknown";
        table.kindLabel = KIND_LABELS.getOrDefault(table.kind, "普通表");
    }

    /* ==================== 2. 规划 ==================== */

    public BridgeDataset plan(String id, String instruction) {
        BridgeDataset dataset = store.load(id);
        String text = instruction == null ? "" : instruction.trim();
        dataset.instruction = text;

        BridgeDataset.Plan plan = new BridgeDataset.Plan();
        plan.plannedAt = store.now();
        long start = System.currentTimeMillis();

        int totalColumns = dataset.tables.stream().mapToInt(t -> t.columns.size()).sum();
        plan.steps.add(step("scan_dataset", "扫描数据集",
                "遍历目录，找到 " + dataset.tables.size() + " 张表、" + totalColumns + " 个字段",
                System.currentTimeMillis() - start));

        long t = System.currentTimeMillis();
        plan.steps.add(step("parse_sheets", "解析表格",
                "按表头识别规则读出每张表的列、行数和样例值（" + totalColumns + " 列）", 0));

        t = System.currentTimeMillis();
        plan.steps.add(step("detect_kind", "识别业务表", kindSummary(dataset), 0));

        t = System.currentTimeMillis();
        List<BridgeDataset.Align> aligns = align(dataset);
        Set<String> labels = new LinkedHashSet<>();
        for (BridgeDataset.Align align : aligns) labels.add(align.label);
        plan.aligns = aligns;
        plan.steps.add(step("align_fields", "字段语义对齐",
                "把不同系统的 " + aligns.size() + " 个列名对到 " + labels.size() + " 个标准字段，"
                        + crossSystemHint(dataset, aligns), 0));

        t = System.currentTimeMillis();
        BridgeDataset.Table primary = pickPrimary(dataset, aligns);
        Map<String, String> reps = tableRep(dataset, aligns, primary);
        plan.sources = buildSources(dataset, primary, reps);
        plan.joinKey = joinKeyOf(primary, aligns, dataset);
        plan.joinLabel = plan.joinKey == null ? "" : labelOfField(plan.joinKey);
        plan.steps.add(step("join_tables", "跨表关联",
                primary == null ? "只有一张表，跳过关联"
                        : "以 " + primary.file + " 为主表，按「" + plan.joinLabel + "」左连接其他表", 0));
        plan.notes.addAll(duplicateKindNotes(dataset, reps));

        t = System.currentTimeMillis();
        plan.computes = buildComputes(dataset, aligns, text, labels);
        plan.filters = buildFilters(dataset, aligns, text, labels, plan);
        plan.steps.add(step("compute", "业务计算与筛选",
                plan.computes.isEmpty() ? "没有识别到需要计算的列，按原样标准化"
                        : "生成 " + plan.computes.size() + " 个计算列：" + String.join("、",
                        plan.computes.stream().map(c -> c.name).toList()), 0));

        t = System.currentTimeMillis();
        plan.anomalyRules = buildAnomalyRules(text, labels);
        plan.steps.add(step("isolate_anomaly", "异常隔离",
                plan.anomalyRules.size() + " 条检查规则，命中就进异常清单，不影响正常行", 0));

        plan.outputColumns = buildOutputColumns(plan, labels, text);
        plan.steps.add(step("standardize_export", "标准化导出",
                "按目标系统习惯输出 " + plan.outputColumns.size() + " 列标准表 + 异常清单（待用户确认后执行）", 0));
        plan.steps.get(plan.steps.size() - 1).state = "pending";

        // 模型只做复核和补充，规则方案本身就是完整可执行的
        ChatModel model = chat.getIfAvailable();
        if (model != null) {
            try {
                String answer = model.chat(refinePrompt(dataset, text, plan));
                applyModelPlan(plan, answer, labels, text);
                plan.mode = "llm";
                plan.steps.add(step("model_review", "大模型复核方案",
                        "结合自然语言目标检查关联键、计算列和筛选条件", 0));
            } catch (Exception e) {
                plan.notes.add("大模型复核没成功，已用规则方案：" + e.getMessage());
                plan.mode = "rules";
            }
        } else {
            plan.notes.add("当前没有启用大模型，方案完全由规则生成，可在配置里打开 app.llm.enabled");
        }

        plan.summary = plan.summary == null || plan.summary.isBlank() ? autoSummary(dataset, plan) : plan.summary;
        plan.notes.addAll(missingNotes(text, aligns));
        dataset.plan = plan;
        dataset.status = "planned";
        store.save(dataset);
        log.info("桥接数据集 {} 规划完成：{} 张表，{} 个对齐字段，模式 {}",
                dataset.id, dataset.tables.size(), aligns.size(), plan.mode);
        return dataset;
    }

    private BridgeDataset.Step step(String tool, String title, String detail, long ms) {
        BridgeDataset.Step step = new BridgeDataset.Step();
        step.tool = tool;
        step.title = title;
        step.detail = detail;
        step.ms = ms;
        return step;
    }

    private String kindSummary(BridgeDataset dataset) {
        List<String> parts = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            parts.add(table.file + " → " + table.kindLabel);
        }
        return String.join("；", parts);
    }

    private List<BridgeDataset.Align> align(BridgeDataset dataset) {
        Map<String, String> custom = customRuleMap();
        List<BridgeDataset.Align> aligns = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            Map<String, BridgeDataset.Align> best = new LinkedHashMap<>();
            for (String column : table.columns) {
                BridgeDataset.Align align = matchField(table, column, custom);
                if (align == null) continue;
                BridgeDataset.Align old = best.get(align.field);
                if (old == null || align.confidence > old.confidence) best.put(align.field, align);
            }
            aligns.addAll(best.values());
        }
        return aligns;
    }

    private Map<String, String> customRuleMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map<String, String> rule : store.rules()) {
            String alias = rule.get("alias");
            String standard = rule.get("standard");
            if (alias != null && standard != null) map.put(TextUtils.norm(alias), standard);
        }
        return map;
    }

    private BridgeDataset.Align matchField(BridgeDataset.Table table, String column, Map<String, String> custom) {
        String key = TextUtils.norm(column);
        if (key.isEmpty()) return null;
        String customField = custom.get(key);
        if (customField != null) {
            return align(customField, table.id, column, "自定义", 1.0);
        }
        for (Std std : STD) {
            for (String alias : std.aliases()) {
                if (TextUtils.norm(alias).equals(key)) {
                    return align(std.key(), table.id, column, "规则", 1.0);
                }
            }
        }
        // 「天」这种单字列名很常见，只允许完全一致，不做包含匹配（否则“号/名”之类的列会乱套）
        if (key.length() < 2) return null;
        for (Std std : STD) {
            for (String alias : std.aliases()) {
                String aliasKey = TextUtils.norm(alias);
                if (aliasKey.length() >= 2 && (key.contains(aliasKey) || aliasKey.contains(key))) {
                    return decorated(key) ? null : align(std.key(), table.id, column, "规则", 0.8);
                }
            }
        }
        return null;
    }

    /**
     * 「2月已享年假天数」「剩余年假可休」这类带修饰词的列，名字里虽然有“天/小时”，
     * 但含义是另一种口径，交给包含匹配会被算错，这里直接不认。
     */
    private boolean decorated(String key) {
        for (String word : List.of("已享", "已休", "享有", "剩余", "余额", "累计", "上限", "备注", "在职", "合计享")) {
            if (key.contains(word)) return true;
        }
        return false;
    }

    private BridgeDataset.Align align(String field, String tableId, String column, String origin, double confidence) {
        BridgeDataset.Align align = new BridgeDataset.Align();
        align.field = field;
        align.label = labelOfField(field);
        align.tableId = tableId;
        align.column = column;
        align.origin = origin;
        align.confidence = confidence;
        return align;
    }

    private String labelOfField(String field) {
        return STD.stream().filter(s -> s.key().equals(field)).map(Std::label).findFirst().orElse(field);
    }

    private String crossSystemHint(BridgeDataset dataset, List<BridgeDataset.Align> aligns) {
        List<String> parts = new ArrayList<>();
        for (BridgeDataset.Align align : aligns) {
            if (!"emp_no".equals(align.field) && !"name".equals(align.field)) continue;
            dataset.tables.stream().filter(t -> t.id.equals(align.tableId)).findFirst()
                    .ifPresent(t -> parts.add(t.file + "「" + align.column + "」→「" + align.label + "」"));
        }
        if (parts.isEmpty()) return "没有识别出跨系统的同义字段";
        return "例如 " + String.join("，", parts.stream().limit(4).toList());
    }

    private BridgeDataset.Table pickPrimary(BridgeDataset dataset, List<BridgeDataset.Align> aligns) {
        BridgeDataset.Table best = null;
        for (BridgeDataset.Table table : dataset.tables) {
            boolean hasKey = hasField(aligns, table.id, "emp_no");
            if (!hasKey && !hasField(aligns, table.id, "name")) continue;
            if (best == null) {
                best = table;
                continue;
            }
            boolean bestKey = hasField(aligns, best.id, "emp_no");
            int score = (hasKey ? 2 : 0) + ("hr_employee".equals(table.kind) ? 1 : 0);
            int bestScore = (bestKey ? 2 : 0) + ("hr_employee".equals(best.kind) ? 1 : 0);
            if (score > bestScore || (score == bestScore && table.rows > best.rows)) best = table;
        }
        if (best == null && !dataset.tables.isEmpty()) {
            best = dataset.tables.stream().max(Comparator.comparingInt(t -> t.rows)).orElse(dataset.tables.get(0));
        }
        return best;
    }

    private boolean hasField(List<BridgeDataset.Align> aligns, String tableId, String field) {
        return aligns.stream().anyMatch(a -> a.tableId.equals(tableId) && a.field.equals(field));
    }

    /**
     * 同一类业务表常常不止一张：OA 导出的请假单，和 HR 系统用来导入的请假模板，其实是同一批记录。
     * 各算一遍就会翻倍，所以每类只留字段最全的一张，其余标成备选表。
     * 返回 tableId → 实际参与计算的那张表的 id（自己）。
     */
    private Map<String, String> tableRep(BridgeDataset dataset, List<BridgeDataset.Align> aligns,
                                         BridgeDataset.Table primary) {
        Map<String, String> repByKind = new LinkedHashMap<>();
        Map<String, String> repOf = new LinkedHashMap<>();
        for (BridgeDataset.Table table : dataset.tables) {
            String kind = table.kind == null ? "unknown" : table.kind;
            boolean pinned = primary != null && primary.id.equals(table.id);
            if (pinned || "unknown".equals(kind)) {
                if (pinned) repByKind.put(kind, table.id);
                repOf.put(table.id, table.id);
                continue;
            }
            String current = repByKind.get(kind);
            if (current == null) {
                repByKind.put(kind, table.id);
                repOf.put(table.id, table.id);
                continue;
            }
            BridgeDataset.Table existing = byId(dataset, current);
            int fields = fieldCount(aligns, table.id);
            int existingFields = fieldCount(aligns, current);
            if (fields > existingFields || (fields == existingFields && existing != null && table.rows > existing.rows)) {
                repByKind.put(kind, table.id);
                repOf.put(table.id, table.id);
                repOf.put(current, table.id);
            } else {
                repOf.put(table.id, current);
            }
        }
        return repOf;
    }

    private List<String> duplicateKindNotes(BridgeDataset dataset, Map<String, String> reps) {
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, String> entry : reps.entrySet()) {
            if (entry.getKey().equals(entry.getValue())) continue;
            BridgeDataset.Table skipped = byId(dataset, entry.getKey());
            BridgeDataset.Table used = byId(dataset, entry.getValue());
            if (skipped == null || used == null) continue;
            notes.add(skipped.file + " 和 " + used.file + " 属于同一类表（" + skipped.kindLabel
                    + "），本次只用字段更全的 " + used.file + "，前者不重复计入，避免同一批数据算两遍");
        }
        return notes;
    }

    private int fieldCount(List<BridgeDataset.Align> aligns, String tableId) {
        return (int) aligns.stream().filter(a -> a.tableId.equals(tableId)).count();
    }

    private BridgeDataset.Table byId(BridgeDataset dataset, String tableId) {
        return dataset.tables.stream().filter(t -> t.id.equals(tableId)).findFirst().orElse(null);
    }

    private List<BridgeDataset.Source> buildSources(BridgeDataset dataset, BridgeDataset.Table primary,
                                                    Map<String, String> reps) {
        List<BridgeDataset.Source> sources = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            BridgeDataset.Source source = new BridgeDataset.Source();
            source.tableId = table.id;
            if (reps != null && !table.id.equals(reps.getOrDefault(table.id, table.id))) {
                source.role = "备选表";
                source.reason = "同类表中已有字段更全的一张参与计算，这张只做留档";
            } else if (primary != null && primary.id.equals(table.id)) {
                source.role = "主表";
                source.reason = "行数最多且带关联键，作为桥接的主表";
            } else if (primary != null) {
                source.role = "关联表";
                source.reason = table.kindLabel + "，按关联键并入";
            } else {
                source.role = "单表";
                source.reason = "没有其他表可关联，直接标准化";
            }
            sources.add(source);
        }
        return sources;
    }

    private String joinKeyOf(BridgeDataset.Table primary, List<BridgeDataset.Align> aligns, BridgeDataset dataset) {
        if (primary == null || dataset.tables.size() <= 1) return null;
        if (hasField(aligns, primary.id, "emp_no")) {
            boolean otherHasKey = dataset.tables.stream()
                    .anyMatch(t -> !t.id.equals(primary.id) && hasField(aligns, t.id, "emp_no"));
            if (otherHasKey) return "emp_no";
        }
        if (hasField(aligns, primary.id, "name")) return "name";
        return null;
    }

    private List<BridgeDataset.Compute> buildComputes(BridgeDataset dataset, List<BridgeDataset.Align> aligns,
                                                      String text, Set<String> labels) {
        List<BridgeDataset.Compute> computes = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        // 指令里点了名的口径优先；一句都没提才看数据集里有什么表
        boolean mentionsSalary = lower.contains("工资") || lower.contains("薪资") || lower.contains("薪酬");
        boolean mentionsOvertime = lower.contains("加班");
        boolean mentionsLeave = lower.contains("请假") || lower.contains("缺勤") || lower.contains("休假");
        boolean anyMention = mentionsSalary || mentionsOvertime || mentionsLeave;

        boolean salary = mentionsSalary || (!anyMention && hasKind(dataset, "erp_salary"));
        if (salary) {
            List<String> plus = new ArrayList<>();
            for (String label : List.of("基本工资", "绩效", "补贴")) {
                if (labels.contains(label)) plus.add(label);
            }
            List<String> minus = new ArrayList<>();
            if (labels.contains("扣款")) minus.add("扣款");
            if (!plus.isEmpty()) {
                BridgeDataset.Compute compute = new BridgeDataset.Compute();
                compute.name = lower.contains("实发") ? "实发工资" : "应发工资";
                compute.plus = plus;
                compute.minus = minus;
                computes.add(compute);
            }
        }
        boolean overtime = mentionsOvertime || (!anyMention && hasKind(dataset, "oa_overtime"));
        if (overtime && labels.contains("加班小时")) {
            BridgeDataset.Compute compute = new BridgeDataset.Compute();
            compute.name = "合计加班小时";
            compute.plus = new ArrayList<>(List.of("加班小时"));
            computes.add(compute);
        }
        boolean leave = mentionsLeave || (!anyMention && hasKind(dataset, "oa_leave"));
        if (leave) {
            if (labels.contains("天数")) {
                BridgeDataset.Compute compute = new BridgeDataset.Compute();
                compute.name = "合计请假天数";
                compute.plus = new ArrayList<>(List.of("天数"));
                computes.add(compute);
            }
            if (labels.contains("请假小时")) {
                BridgeDataset.Compute compute = new BridgeDataset.Compute();
                compute.name = "合计请假小时";
                compute.plus = new ArrayList<>(List.of("请假小时"));
                computes.add(compute);
            }
        }
        return computes;
    }

    private boolean hasKind(BridgeDataset dataset, String kind) {
        return dataset.tables.stream().anyMatch(t -> kind.equals(t.kind));
    }

    /**
     * 指令里点名要的表，数据集里可能压根没有。先说清楚，
     * 免得用户看到结果才发现“薪资”这一列全是空的。
     */
    private List<String> missingNotes(String text, List<BridgeDataset.Align> aligns) {
        List<String> notes = new ArrayList<>();
        boolean hasSalaryField = aligns.stream().anyMatch(a -> List.of("base_salary", "performance", "allowance",
                "deduction", "gross_salary", "net_salary").contains(a.field));
        if ((text.contains("工资") || text.contains("薪资") || text.contains("薪酬")) && !hasSalaryField) {
            notes.add("数据集里没有识别到薪资相关字段，工资列没法算，建议把 ERP 薪资导出表一起放进压缩包。");
        }
        boolean hasOnboard = aligns.stream().anyMatch(a -> "onboard_date".equals(a.field));
        if ((text.contains("入职") || text.contains("花名册")) && !hasOnboard) {
            notes.add("数据集里没有识别到入职日期字段，这类信息会按缺失处理，命中就进异常清单。");
        }
        return notes;
    }

    private List<String> buildFilters(BridgeDataset dataset, List<BridgeDataset.Align> aligns, String text,
                                      Set<String> labels, BridgeDataset.Plan plan) {
        List<String> filters = new ArrayList<>();
        Matcher iso = RANGE_ISO.matcher(text);
        if (iso.find()) {
            plan.dateFrom = iso.group(1).replace('/', '-');
            plan.dateTo = iso.group(2).replace('/', '-');
        } else {
            Matcher cn = RANGE_CN.matcher(text);
            if (cn.find()) {
                String fromYear = cn.group(1) != null ? cn.group(1) : String.valueOf(LocalDate.now().getYear());
                String toYear = cn.group(4) != null ? cn.group(4) : fromYear;
                plan.dateFrom = String.format("%s-%02d-%02d", fromYear, Integer.parseInt(cn.group(2)),
                        cn.group(3) == null ? 1 : Integer.parseInt(cn.group(3)));
                plan.dateTo = String.format("%s-%02d-%02d", toYear, Integer.parseInt(cn.group(5)),
                        cn.group(6) == null ? 28 : Integer.parseInt(cn.group(6)));
            }
        }
        if (plan.dateFrom != null) {
            for (String candidate : List.of("入职日期", "开始日期", "加班日期")) {
                if (labels.contains(candidate)) {
                    plan.dateField = candidate;
                    break;
                }
            }
            if (plan.dateField != null) {
                filters.add("只保留" + plan.dateField + "在 " + plan.dateFrom + " 到 " + plan.dateTo + " 之间的行");
            }
        }
        if (labels.contains("在职状态")) {
            if (text.contains("在职") && !text.contains("离职")) {
                plan.statusFilter = "在职";
                filters.add("只保留在职状态的员工");
            } else if (text.contains("离职")) {
                plan.statusFilter = "离职";
                filters.add("只保留离职状态的员工");
            }
        }
        if (filters.isEmpty()) filters.add("不加额外筛选，全部行参与桥接");
        return filters;
    }

    private List<String> buildAnomalyRules(String text, Set<String> labels) {
        List<String> rules = new ArrayList<>(List.of(
                "关联键为空或对不上主表的行",
                "数值列出现非数字内容",
                "关键字段疑似乱码"
        ));
        if (labels.contains("入职日期")) {
            rules.add("入职日期为空或格式无法识别");
        }
        if (text.contains("缺失") || text.contains("异常")) {
            rules.add("业务要求的关键信息缺失（按指令判定）");
        }
        return rules;
    }

    private List<String> buildOutputColumns(BridgeDataset.Plan plan, Set<String> labels, String text) {
        List<String> columns = new ArrayList<>();
        for (String label : List.of("工号", "姓名", "部门", "职位", "入职日期")) {
            if (labels.contains(label)) columns.add(label);
        }
        for (BridgeDataset.Compute compute : plan.computes) {
            if (!columns.contains(compute.name)) columns.add(compute.name);
        }
        // 明细列只在指令点到这一块时才带：问加班却带一堆请假列，反而给导入添麻烦
        String lower = text.toLowerCase(Locale.ROOT);
        boolean talkLeave = lower.contains("请假") || lower.contains("缺勤") || lower.contains("休假") || lower.contains("假");
        boolean talkOvertime = lower.contains("加班");
        boolean talkAnnual = lower.contains("年假") || lower.contains("调休");
        boolean talkStatus = lower.contains("在职") || lower.contains("离职");
        if (talkLeave) addIfAbsent(columns, labels, List.of("请假类型", "天数", "请假小时"));
        if (talkOvertime) addIfAbsent(columns, labels, List.of("加班小时"));
        if (talkAnnual) addIfAbsent(columns, labels, List.of("年假余额"));
        if (talkStatus) addIfAbsent(columns, labels, List.of("在职状态"));
        columns.add("数据来源");
        return columns;
    }

    private void addIfAbsent(List<String> columns, Set<String> labels, List<String> wanted) {
        for (String label : wanted) {
            if (!labels.contains(label)) continue;
            boolean covered = columns.stream().anyMatch(c -> c.contains(label) || label.contains(c));
            if (!covered) columns.add(label);
        }
    }

    private String autoSummary(BridgeDataset dataset, BridgeDataset.Plan plan) {
        String join = plan.joinKey == null ? "单表标准化" : "按「" + plan.joinLabel + "」桥接";
        return "读取 " + dataset.tables.size() + " 张表，" + join + "，输出 "
                + plan.outputColumns.size() + " 列标准表；命中异常的行单独出清单。";
    }

    /* ==================== 3. 模型复核（可选） ==================== */

    private String refinePrompt(BridgeDataset dataset, String instruction, BridgeDataset.Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是跨系统表格桥接智能体的规划器。只输出 JSON，不要解释。\n");
        sb.append("用户目标：").append(instruction.isEmpty() ? "（未填写）" : instruction).append("\n");
        sb.append("数据集表格：\n");
        for (BridgeDataset.Table table : dataset.tables) {
            sb.append("- ").append(table.id).append(" ").append(table.file)
                    .append(" 类型=").append(table.kindLabel)
                    .append(" 行数=").append(table.rows)
                    .append(" 列=").append(String.join("/", table.columns)).append("\n");
        }
        sb.append("已对齐的标准字段：").append(String.join("、", plan.aligns.stream()
                .map(a -> a.field + "←" + a.column).toList())).append("\n");
        sb.append("输出 JSON 结构：{\"join_key\":\"emp_no 或 name 或空\",")
                .append("\"compute\":[{\"name\":\"应发工资\",\"plus\":[\"基本工资\",\"绩效\"],\"minus\":[\"扣款\"]}],")
                .append("\"filters\":[\"只保留在职员工\"],\"summary\":\"一句话说明方案\"}\n");
        sb.append("要求：compute 里的列名必须来自上面的标准字段；不确定就不要编。");
        return sb.toString();
    }

    private void applyModelPlan(BridgeDataset.Plan plan, String answer, Set<String> labels, String instruction) {
        JsonNode node = parseJson(answer);
        if (node == null) {
            plan.notes.add("大模型返回的内容不是 JSON，已忽略");
            return;
        }
        String joinKey = node.path("join_key").asText("");
        if (joinKey.contains("emp") || joinKey.contains("工号")) plan.joinKey = "emp_no";
        else if (joinKey.contains("name") || joinKey.contains("姓名")) plan.joinKey = "name";
        if (plan.joinKey != null) plan.joinLabel = labelOfField(plan.joinKey);

        List<BridgeDataset.Compute> computes = new ArrayList<>();
        for (JsonNode item : node.path("compute")) {
            BridgeDataset.Compute compute = new BridgeDataset.Compute();
            compute.name = item.path("name").asText("");
            compute.plus = validLabels(item.path("plus"), labels);
            compute.minus = validLabels(item.path("minus"), labels);
            compute.origin = "AI";
            if (!compute.name.isBlank() && !compute.plus.isEmpty()) computes.add(compute);
        }
        if (!computes.isEmpty()) plan.computes = computes;

        List<String> extraFilters = new ArrayList<>();
        for (JsonNode item : node.path("filters")) {
            String text = item.asText("");
            if (!text.isBlank()) extraFilters.add(text);
        }
        for (String filter : extraFilters) {
            if (!plan.filters.contains(filter)) plan.filters.add(filter);
        }
        if (node.path("summary").asText("").length() > 4) {
            plan.summary = node.path("summary").asText();
        }
        plan.outputColumns = buildOutputColumns(plan, labels, instruction);
    }

    private List<String> validLabels(JsonNode array, Set<String> labels) {
        List<String> list = new ArrayList<>();
        for (JsonNode item : array) {
            String label = item.asText("");
            if (labels.contains(label) && !list.contains(label)) list.add(label);
        }
        return list;
    }

    private JsonNode parseJson(String answer) {
        if (answer == null) return null;
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return mapper.readTree(answer.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== 4. 执行 ==================== */

    public BridgeDataset run(String id) {
        BridgeDataset dataset = store.load(id);
        if (dataset.plan == null) {
            throw new IllegalArgumentException("还没有规划方案，先让智能体规划");
        }
        dataset.status = "running";
        store.save(dataset);

        long start = System.currentTimeMillis();
        BridgeDataset.Plan plan = dataset.plan;
        plan.confirmed = true;

        Map<String, ExcelService.SheetData> sheets = new LinkedHashMap<>();
        Map<String, List<BridgeDataset.Align>> alignsByTable = new LinkedHashMap<>();
        for (BridgeDataset.Table table : dataset.tables) {
            Path path = store.filesDir(dataset.id).resolve(table.stored);
            if (!Files.exists(path)) continue;
            try {
                sheets.put(table.id, excel.read(path, catalog, settings.mergedAliases(), true));
            } catch (Exception e) {
                plan.notes.add("读取 " + table.file + " 失败：" + e.getMessage());
                continue;
            }
            alignsByTable.put(table.id, plan.aligns.stream().filter(a -> a.tableId.equals(table.id)).toList());
        }

        // 每张表的行先统一成“标准字段 → 值”
        Map<String, List<Map<String, String>>> stdRows = new LinkedHashMap<>();
        for (Map.Entry<String, ExcelService.SheetData> entry : sheets.entrySet()) {
            List<BridgeDataset.Align> aligns = alignsByTable.getOrDefault(entry.getKey(), List.of());
            List<Map<String, String>> rows = new ArrayList<>();
            for (ExcelService.RowData row : entry.getValue().rows()) {
                Map<String, String> values = new LinkedHashMap<>();
                for (BridgeDataset.Align align : aligns) {
                    String value = row.values().getOrDefault(align.column, "");
                    if (!TextUtils.isBlank(value)) values.put(align.field, value);
                }
                for (Map.Entry<String, String> cell : row.values().entrySet()) {
                    if (!TextUtils.isBlank(cell.getValue())) values.put("col:" + cell.getKey(), cell.getValue());
                }
                if (!values.isEmpty()) rows.add(values);
            }
            stdRows.put(entry.getKey(), rows);
        }

        Map<String, String> labelToField = new LinkedHashMap<>();
        for (Std std : STD) labelToField.put(std.label(), std.key());

        LocalDate fromDate = TextUtils.parseDate(plan.dateFrom);
        LocalDate toDate = TextUtils.parseDate(plan.dateTo);
        // 日期区间和状态筛选要落在“原始记录”上：先筛再按工号聚合，
        // 否则一个人多天记录合并成一行后，日期就只剩第一条，判断没有意义。
        if (fromDate != null || plan.statusFilter != null) {
            for (List<Map<String, String>> rows : stdRows.values()) {
                rows.removeIf(row -> filteredOut(row, plan, fromDate, toDate, labelToField));
            }
        }

        BridgeDataset.Table primary = null;
        BridgeDataset.Source primarySource = plan.sources.stream()
                .filter(s -> "主表".equals(s.role)).findFirst().orElse(null);
        if (primarySource != null) {
            primary = dataset.tables.stream().filter(t -> t.id.equals(primarySource.tableId)).findFirst().orElse(null);
        }
        if (primary == null) {
            primary = dataset.tables.stream().max(Comparator.comparingInt(t -> t.rows)).orElse(null);
        }
        if (primary == null || !stdRows.containsKey(primary.id)) {
            throw new IllegalStateException("主表读不出来，请重新上传数据集");
        }

        // 同类表只让字段最全的一张参与计算，其余标了“备选表”，这里直接略过
        for (BridgeDataset.Source source : plan.sources) {
            if ("备选表".equals(source.role)) stdRows.remove(source.tableId);
        }

        // 关联表按工号聚合成一条：同一个人有多条加班/请假记录时，数值列累加，文本列取第一条非空
        Map<String, Map<String, String>> factAgg = new LinkedHashMap<>();
        if (plan.joinKey != null) {
            for (Map.Entry<String, List<Map<String, String>>> entry : stdRows.entrySet()) {
                if (entry.getKey().equals(primary.id)) continue;
                for (Map<String, String> row : entry.getValue()) {
                    String key = TextUtils.trim(row.get(plan.joinKey));
                    if (TextUtils.isBlank(key)) continue;
                    Map<String, String> target = factAgg.computeIfAbsent(
                            entry.getKey() + "|" + TextUtils.norm(key), k -> new LinkedHashMap<>());
                    mergeSum(target, row);
                }
            }
        }

        // 只有真正被用到的关联表才要求能对上：这单只统计加班时，
        // 没请过假的人不该被当成异常，否则异常清单会被无关的表撑满。
        // 身份列（工号姓名部门）哪张表都有，不能算“用到了这张表”。
        Set<String> usedLabels = new LinkedHashSet<>();
        for (BridgeDataset.Compute compute : plan.computes) {
            usedLabels.addAll(compute.plus);
            usedLabels.addAll(compute.minus);
        }
        for (String column : plan.outputColumns) {
            usedLabels.add(column);
        }
        usedLabels.removeAll(List.of("工号", "姓名", "部门", "职位", "入职日期", "在职状态", "数据来源"));
        // 主数据表（员工/薪资/年假）一人一行，缺了就是信息缺失；
        // 流水表（加班、请假）没记录等于这个人这月没加班/没请假，不该算异常。
        Map<String, String> requiredTables = new LinkedHashMap<>();
        for (String tableId : stdRows.keySet()) {
            if (tableId.equals(primary.id)) continue;
            BridgeDataset.Table table = byId(dataset, tableId);
            if (table == null || !MASTER_KINDS.contains(table.kind)) continue;
            boolean needed = plan.aligns.stream()
                    .anyMatch(a -> a.tableId.equals(tableId) && usedLabels.contains(a.label));
            if (needed) requiredTables.put(tableId, table.file);
        }

        List<List<Object>> okCells = new ArrayList<>();
        List<List<Object>> anomalyCells = new ArrayList<>();
        List<Map<String, String>> preview = new ArrayList<>();
        List<Map<String, String>> anomalyPreview = new ArrayList<>();
        List<String> sourceLabels = dataset.tables.stream()
                .filter(t -> stdRows.containsKey(t.id)).map(t -> t.kindLabel).distinct().toList();

        // 主表也按关联键聚合成“一人一行”，同一个工号的多条记录累加，避免重复计数
        for (GroupRow ref : groupRows(stdRows.get(primary.id), plan.joinKey)) {
            Map<String, String> merged = new LinkedHashMap<>(ref.values());
            String key = ref.key();
            List<String> missingTables = new ArrayList<>();
            if (plan.joinKey != null && !TextUtils.isBlank(key)) {
                for (String tableId : stdRows.keySet()) {
                    if (tableId.equals(primary.id)) continue;
                    Map<String, String> fact = factAgg.get(tableId + "|" + TextUtils.norm(key));
                    if (fact == null) {
                        if (requiredTables.containsKey(tableId)) missingTables.add(requiredTables.get(tableId));
                        continue;
                    }
                    for (Map.Entry<String, String> cell : fact.entrySet()) {
                        if (TextUtils.isBlank(merged.get(cell.getKey()))) merged.put(cell.getKey(), cell.getValue());
                    }
                }
            }

            List<String> reasons = new ArrayList<>();
            if (plan.joinKey != null) {
                if (TextUtils.isBlank(key)) {
                    reasons.add("缺少" + plan.joinLabel);
                } else if (!missingTables.isEmpty()) {
                    reasons.add("在 " + String.join("、", missingTables) + " 里对不上这个" + plan.joinLabel);
                }
            }
            String onboard = valueOfLabel(merged, "入职日期", labelToField);
            if (!TextUtils.isBlank(onboard) && TextUtils.parseDate(onboard) == null) {
                reasons.add("入职日期无法识别");
            }
            if (TextUtils.isBlank(onboard) && (dataset.instruction.contains("入职") || dataset.instruction.contains("缺失"))) {
                reasons.add("入职信息缺失");
            }
            String empNo = valueOfLabel(merged, "工号", labelToField);
            String name = valueOfLabel(merged, "姓名", labelToField);
            if (TextUtils.garbled(empNo) || TextUtils.garbled(name)) reasons.add("疑似乱码");

            Map<String, String> computed = new LinkedHashMap<>();
            for (BridgeDataset.Compute compute : plan.computes) {
                Double value = computeValue(merged, compute, labelToField);
                if (value == null) {
                    String sample = firstValue(merged, compute.plus, labelToField);
                    if (!TextUtils.isBlank(sample) && TextUtils.parseNumber(sample) == null) {
                        reasons.add(compute.name + " 的原始数值不是数字");
                    }
                }
                computed.put(compute.name, value == null ? "" : TextUtils.numberText(value));
            }

            Map<String, String> out = new LinkedHashMap<>();
            for (String column : plan.outputColumns) {
                if ("数据来源".equals(column)) {
                    out.put(column, String.join("+", sourceLabels));
                } else if (computed.containsKey(column)) {
                    out.put(column, computed.get(column));
                } else {
                    out.put(column, normalizeDate(valueOfLabel(merged, column, labelToField)));
                }
            }

            if (reasons.isEmpty()) {
                okCells.add(cellsOf(plan.outputColumns, out));
                if (preview.size() < 20) preview.add(out);
            } else {
                Map<String, String> anomaly = new LinkedHashMap<>();
                anomaly.put("工号", empNo);
                anomaly.put("姓名", name);
                anomaly.put("异常原因", String.join("；", reasons));
                anomaly.put("来源表", primary.file);
                anomaly.put("原值摘要", summarize(merged, labelToField));
                anomalyCells.add(cellsOf(anomaly.keySet(), anomaly));
                if (anomalyPreview.size() < 20) anomalyPreview.add(anomaly);
            }
        }

        // 关联表里有、主表里没有的工号：这才是真正“对不上”的行，单独挑出来
        if (plan.joinKey != null) {
            Set<String> primaryKeys = new LinkedHashSet<>();
            for (Map<String, String> row : stdRows.get(primary.id)) {
                String value = TextUtils.norm(TextUtils.trim(row.get(plan.joinKey)));
                if (!value.isEmpty()) primaryKeys.add(value);
            }
            for (BridgeDataset.Table table : dataset.tables) {
                if (table.id.equals(primary.id) || !stdRows.containsKey(table.id)) continue;
                boolean skipped = plan.sources.stream()
                        .anyMatch(s -> s.tableId.equals(table.id) && "备选表".equals(s.role));
                if (skipped) continue;
                for (Map<String, String> row : stdRows.get(table.id)) {
                    if (anomalyCells.size() >= ANOMALY_LIMIT) break;
                    String rawKey = TextUtils.trim(row.get(plan.joinKey));
                    if (TextUtils.isBlank(rawKey) || primaryKeys.contains(TextUtils.norm(rawKey))) continue;
                    Map<String, String> anomaly = new LinkedHashMap<>();
                    anomaly.put("工号", valueOfLabel(row, "工号", labelToField));
                    anomaly.put("姓名", valueOfLabel(row, "姓名", labelToField));
                    anomaly.put("异常原因", "在 " + table.file + " 里，这个" + plan.joinLabel + "在主表中找不到");
                    anomaly.put("来源表", table.file);
                    anomaly.put("原值摘要", summarize(row, labelToField));
                    anomalyCells.add(cellsOf(anomaly.keySet(), anomaly));
                    if (anomalyPreview.size() < 20) anomalyPreview.add(anomaly);
                }
            }
        }

        Path outDir = store.outDir(dataset.id);
        try {
            Files.createDirectories(outDir);
            excel.writeXlsx(outDir.resolve("标准表.xlsx"), plan.outputColumns, okCells);
            excel.writeXls(outDir.resolve("标准表.xls"), plan.outputColumns, okCells);
        } catch (IOException e) {
            throw new IllegalStateException("导出标准表失败：" + e.getMessage(), e);
        }
        List<String> anomalyColumns = List.of("工号", "姓名", "异常原因", "来源表", "原值摘要");
        try {
            excel.writeXlsx(outDir.resolve("异常清单.xlsx"), anomalyColumns, anomalyCells);
        } catch (IOException e) {
            throw new IllegalStateException("导出异常清单失败：" + e.getMessage(), e);
        }

        BridgeDataset.Result result = new BridgeDataset.Result();
        result.tableCount = dataset.tables.size();
        result.totalRows = okCells.size() + anomalyCells.size();
        result.okRows = okCells.size();
        result.anomalyRows = anomalyCells.size();
        result.columns = plan.outputColumns;
        result.preview = preview;
        result.anomalyPreview = anomalyPreview;
        result.artifacts = List.of("标准表.xlsx", "标准表.xls", "异常清单.xlsx");
        result.elapsedMs = System.currentTimeMillis() - start;
        result.finishedAt = store.now();
        result.mode = plan.mode;

        // 记忆模块：把这次执行的进度和统计一起写回 json，刷新页面还能看到
        plan.steps.forEach(step -> step.state = "done");
        dataset.result = result;
        dataset.status = "done";
        store.save(dataset);
        log.info("桥接数据集 {} 执行完成：{} 行，异常 {} 行，耗时 {}ms",
                dataset.id, result.okRows, result.anomalyRows, result.elapsedMs);
        return dataset;
    }

    /** 目标系统大多只认 yyyy-MM-dd，导出前把各种日期写法统一掉 */
    private String normalizeDate(String value) {
        if (TextUtils.isBlank(value)) return "";
        java.time.LocalDate date = TextUtils.parseDate(value);
        return date == null ? value : TextUtils.formatDate(date);
    }

    /** 在职状态和日期区间都在原始记录上判断，聚合后再判断就失真了 */
    private boolean filteredOut(Map<String, String> row, BridgeDataset.Plan plan, LocalDate from, LocalDate to,
                                Map<String, String> labelToField) {
        if (plan.statusFilter != null) {
            String status = valueOfLabel(row, "在职状态", labelToField);
            if (!TextUtils.isBlank(status) && !status.contains(plan.statusFilter)) return true;
        }
        if (plan.dateField != null && from != null) {
            LocalDate date = TextUtils.parseDate(valueOfLabel(row, plan.dateField, labelToField));
            if (date != null && (date.isBefore(from) || (to != null && date.isAfter(to)))) return true;
        }
        return false;
    }

    /** 聚合后的主表一行：key 为关联键（没有键时为 null） */
    private record GroupRow(String key, Map<String, String> values) {
    }

    /**
     * 主表按关联键合并。同一个工号可能有多条加班/请假记录，
     * 数值列相加、文本列保留第一次出现的值，这样输出才是“一人一行”。
     */
    private List<GroupRow> groupRows(List<Map<String, String>> rows, String joinKey) {
        List<GroupRow> list = new ArrayList<>();
        if (joinKey == null) {
            for (Map<String, String> row : rows) list.add(new GroupRow(null, row));
            return list;
        }
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String key = TextUtils.trim(row.get(joinKey));
            if (TextUtils.isBlank(key)) {
                list.add(new GroupRow(null, row));
                continue;
            }
            String norm = TextUtils.norm(key);
            Map<String, String> group = groups.computeIfAbsent(norm, k -> new LinkedHashMap<>());
            mergeSum(group, row);
            keys.putIfAbsent(norm, key);
        }
        for (Map.Entry<String, Map<String, String>> entry : groups.entrySet()) {
            list.add(new GroupRow(keys.get(entry.getKey()), entry.getValue()));
        }
        return list;
    }

    /** 数值字段跨行累加，其它字段只补空位 */
    private void mergeSum(Map<String, String> target, Map<String, String> row) {
        for (Map.Entry<String, String> cell : row.entrySet()) {
            String field = cell.getKey();
            String value = cell.getValue();
            if (TextUtils.isBlank(value) || !STD_NUMERIC.contains(field)) {
                if (TextUtils.isBlank(target.get(field))) target.put(field, value);
                continue;
            }
            Double incoming = TextUtils.parseNumber(value);
            if (incoming == null) {
                if (TextUtils.isBlank(target.get(field))) target.put(field, value);
                continue;
            }
            Double old = TextUtils.parseNumber(target.get(field));
            target.put(field, TextUtils.numberText((old == null ? 0d : old) + incoming));
        }
    }

    private String valueOfLabel(Map<String, String> merged, String label, Map<String, String> labelToField) {
        String field = labelToField.get(label);
        if (field != null) {
            String value = merged.get(field);
            if (!TextUtils.isBlank(value)) return value;
        }
        String raw = merged.get("col:" + label);
        if (!TextUtils.isBlank(raw)) return raw;
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String key = entry.getKey().startsWith("col:") ? entry.getKey().substring(4) : labelOfField(entry.getKey());
            if (key == null || key.isBlank()) continue;
            if ((key.contains(label) || label.contains(key)) && !TextUtils.isBlank(entry.getValue())) {
                return entry.getValue();
            }
        }
        return "";
    }

    private Double computeValue(Map<String, String> merged, BridgeDataset.Compute compute,
                                Map<String, String> labelToField) {
        Double plus = sumOf(merged, compute.plus, labelToField);
        Double minus = sumOf(merged, compute.minus, labelToField);
        if (plus == null && minus == null) return null;
        return (plus == null ? 0 : plus) - (minus == null ? 0 : minus);
    }

    private Double sumOf(Map<String, String> merged, List<String> labels, Map<String, String> labelToField) {
        double sum = 0;
        boolean any = false;
        for (String label : labels) {
            Double value = TextUtils.parseNumber(valueOfLabel(merged, label, labelToField));
            if (value != null) {
                sum += value;
                any = true;
            }
        }
        return any ? sum : null;
    }

    private String firstValue(Map<String, String> merged, List<String> labels, Map<String, String> labelToField) {
        for (String label : labels) {
            String value = valueOfLabel(merged, label, labelToField);
            if (!TextUtils.isBlank(value)) return value;
        }
        return "";
    }

    private List<Object> cellsOf(java.util.Collection<String> columns, Map<String, String> row) {
        List<Object> cells = new ArrayList<>();
        for (String column : columns) cells.add(row.getOrDefault(column, ""));
        return cells;
    }

    private String summarize(Map<String, String> merged, Map<String, String> labelToField) {
        List<String> parts = new ArrayList<>();
        for (String label : List.of("姓名", "部门", "入职日期", "应发工资", "加班小时")) {
            String value = valueOfLabel(merged, label, labelToField);
            if (!TextUtils.isBlank(value)) parts.add(label + "=" + value);
        }
        return String.join("，", parts);
    }

    /* ==================== 5. 结果查询 ==================== */

    public Map<String, Object> rows(String id, String kind, int page, int size) {
        BridgeDataset dataset = store.load(id);
        if (dataset.result == null) {
            throw new IllegalArgumentException("还没有执行结果");
        }
        boolean anomaly = "anomaly".equalsIgnoreCase(kind);
        Path path = store.outDir(id).resolve(anomaly ? "异常清单.xlsx" : "标准表.xlsx");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("结果文件不存在，可能已被清理，请重新执行");
        }
        ExcelService.SheetData sheet = excel.read(path, catalog, settings.mergedAliases(), false);
        int pageSize = Math.min(Math.max(size, 1), 200);
        int from = Math.max(0, (Math.max(page, 1) - 1) * pageSize);
        int to = Math.min(sheet.rows().size(), from + pageSize);
        List<Map<String, String>> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            list.add(new LinkedHashMap<>(sheet.rows().get(i).values()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("columns", sheet.headers());
        body.put("rows", list);
        body.put("total", sheet.rows().size());
        return body;
    }

    public Path artifact(String id, String name) {
        BridgeDataset dataset = store.load(id);
        if (dataset.result == null || !dataset.result.artifacts.contains(name)) {
            throw new IllegalArgumentException("没有这个导出文件");
        }
        Path path = store.outDir(id).resolve(name).normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("导出文件已不存在，请重新执行");
        }
        return path;
    }

    public BridgeDataset get(String id) {
        return store.load(id);
    }

    public List<BridgeDataset> recent() {
        return store.recent();
    }
}
