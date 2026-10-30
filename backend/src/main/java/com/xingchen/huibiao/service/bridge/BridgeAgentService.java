package com.xingchen.huibiao.service.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.service.ExcelService;
import com.xingchen.huibiao.service.HeaderCatalog;
import com.xingchen.huibiao.service.SettingsService;
import com.xingchen.huibiao.service.governance.AgentRunStore;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 智能体模式：上传一个 zip / 一整个文件夹，只说业务目标，剩下的交给它。
@Service
public class BridgeAgentService {

    private static final Logger log = LoggerFactory.getLogger(BridgeAgentService.class);

    // 标准字段：跨系统的统一叫法，对齐的目标
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

    // 这些标准字段是数值，聚合时按工号累加
    private static final Set<String> STD_NUMERIC = Set.of(
            "base_salary", "performance", "allowance", "deduction", "gross_salary", "net_salary",
            "overtime_hours", "days", "hours", "annual_left", "amount");

    // 主数据类型：一人一行，缺行代表信息缺失，需要进异常清单
    private static final Set<String> MASTER_KINDS = Set.of("hr_employee", "erp_salary", "hr_annual");

    // 异常清单的行数上限：数据集再大也不至于写出一张几万行的清单
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

    // 规划器能调用的原子能力：模型只能从这份清单里挑，挑完的参数由下面的执行器落地
    private static final Map<String, String> TOOL_CATALOG = new LinkedHashMap<>();

    static {
        TOOL_CATALOG.put("scan_dataset", "遍历数据集目录，列出所有表格和业务类型");
        TOOL_CATALOG.put("parse_sheets", "读每张表的表头、行数和样例值");
        TOOL_CATALOG.put("detect_kind", "按文件名和表头判断这是 HR / ERP / OA 的哪张表");
        TOOL_CATALOG.put("align_fields", "把各系统不一样的列名对齐到统一的标准字段");
        TOOL_CATALOG.put("join_tables", "按工号或姓名把多张表关联成一人一行");
        TOOL_CATALOG.put("filter_rows", "按日期区间、在职状态等条件筛掉不符合的行");
        TOOL_CATALOG.put("compute", "跨表聚合与加减计算");
        TOOL_CATALOG.put("isolate_anomaly", "把命中异常检查的行隔离到单独清单");
        TOOL_CATALOG.put("standardize_export", "按目标系统的列顺序导出标准表");
    }

    private static final Map<String, String> TOOL_TITLES = Map.of(
            "scan_dataset", "扫描数据集",
            "parse_sheets", "解析表格",
            "detect_kind", "识别业务表",
            "align_fields", "字段语义对齐",
            "join_tables", "跨表关联",
            "filter_rows", "条件筛选",
            "compute", "业务计算",
            "isolate_anomaly", "异常隔离",
            "standardize_export", "标准化导出"
    );

    private static final Set<String> ANOMALY_TYPES = Set.of("join", "blank", "date", "numeric", "garbled", "duplicate");

    private final BridgeStore store;
    private final ExcelService excel;
    private final HeaderCatalog catalog;
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final ObjectProvider<ChatModel> chat;
    private final BridgeMemory memory;
    private final DataPaths paths;
    // 用户上传的知识库：制度、口径说明、对照表，规划与问答时会按相关度引用
    private final BridgeKnowledge knowledge;
    // 运行现场：真实进度事件、单次运行锁、取消标记与运行预算
    private final BridgeRun run;
    // 运行观测：智能体这一单跑完也要记一笔，和普通模式的编排同一张表
    private final AgentRunStore agentRuns;

    public BridgeAgentService(BridgeStore store, ExcelService excel, HeaderCatalog catalog,
                              SettingsService settings, ObjectMapper mapper, ObjectProvider<ChatModel> chat,
                              BridgeMemory memory, DataPaths paths, BridgeRun run, BridgeKnowledge knowledge,
                              AgentRunStore agentRuns) {
        this.store = store;
        this.excel = excel;
        this.catalog = catalog;
        this.settings = settings;
        this.mapper = mapper;
        this.chat = chat;
        this.memory = memory;
        this.paths = paths;
        this.run = run;
        this.knowledge = knowledge;
        this.agentRuns = agentRuns;
    }

    // 1. 上传 + 扫描

    public BridgeDataset create(String name, String sourceType, List<MultipartFile> uploads) {
        return create(name, sourceType, uploads, null);
    }

    // 建单：access 是这一单的可执行权限（手动审批 / 自动审批 / 完全访问）。
    public BridgeDataset create(String name, String sourceType, List<MultipartFile> uploads,
                                String access) {
        BridgeDataset dataset = new BridgeDataset();
        dataset.id = store.newId();
        dataset.name = name == null || name.isBlank() ? "数据集 " + dataset.id : name.trim();
        dataset.sourceType = sourceType == null || sourceType.isBlank() ? "files" : sourceType;
        dataset.createdAt = store.now();
        dataset.access = normalizeAccess(access);

        int saved = store.materialize(dataset.id, uploads);
        if (saved <= 0) {
            throw new IllegalArgumentException("没有找到可解析的表格：只支持 .xlsx / .xls / .csv，"
                    + "压缩包请用 .zip 且里面直接放表格；.rar / .7z 和带密码的压缩包要先解压再上传");
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
                String kept = seen.get(signature);
                BridgeDataset.Skipped dup = new BridgeDataset.Skipped();
                dup.file = file.getFileName().toString();
                dup.rows = sheet.rows().size();
                dup.columns = new ArrayList<>(sheet.headers());
                dup.duplicateOf = kept;
                Kind kind = classifyOf(dup.file, dup.columns);
                dup.kind = kind.kind();
                dup.kindLabel = kind.label();
                dup.reason = "表头、行数（" + dup.rows + " 行）和首末行都与 " + kept
                        + " 完全一致，判定为同一批数据的另一份导出，为避免同一批记录算两遍没有并入";
                dataset.skipped.add(dup);
                dataset.notes.add(file.getFileName() + " 和 " + kept
                        + " 数据完全相同，只用 " + kept + "，跳过重复的那份");
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
            Kind kind = classifyOf(table.file, table.columns);
            table.kind = kind.kind();
            table.kindLabel = kind.label();
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
                dataset.tables.stream().map(t -> t.file + "（" + t.kindLabel + "）").toList())
                + (dataset.skipped.isEmpty() ? ""
                : "；另有 " + dataset.skipped.size() + " 张重复表已跳过，见表格列表最后的灰色条目"));
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

    // 表头 + 行数 + 首尾行，用来判断两份导出其实是同一批数据
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

    // 业务身份的判断结果：kind 给逻辑用，label 给人看
    private record Kind(String kind, String label) {
    }

    // 按文件名 + 表头给表安一个业务身份，用词都很俗，够用就行
    private Kind classifyOf(String file, List<String> columns) {
        String fileName = TextUtils.norm(file);
        String headerText = TextUtils.norm(String.join("", columns));
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
        String kind = bestScore > 0 ? bestKind : "unknown";
        return new Kind(kind, KIND_LABELS.getOrDefault(kind, "普通表"));
    }

    // 2. 规划

    public BridgeDataset plan(String id, String instruction) {
        BridgeDataset dataset = store.load(id);
        String text = instruction == null ? "" : instruction.trim();
        // 用户自己写的那句单独留着：推断出来的目标里本来就写了“核对重复”，
        // 不能拿它反过来当成“用户要求查重”，否则等于自己给自己加了一条检查
        String userText = text;
        // 没给业务目标就不问了：按数据结构自己推断，自主模式的第一步就是“自己知道要做什么”
        boolean inferred = text.isBlank();
        if (inferred) text = inferGoal(dataset);
        dataset.instruction = text;
        if (inferred) {
            dataset.notes.add("未提供业务目标，已按数据集结构自主推断：" + text);
        }

        BridgeDataset.Plan plan = new BridgeDataset.Plan();
        plan.plannedAt = store.now();
        long start = System.currentTimeMillis();

        // 先看经验记忆里有没有同类数据集：有的话，对齐的缺口直接用它补上，
        // 规划器也能参考上次确认过的关联键、计算列和输出列（规则仍是硬约束，经验只补空白）
        BridgeMemory.Lesson lesson = memory.bestMatch(allColumns(dataset), kindSignature(dataset), lessonScene(dataset));

        // 规则先算一份完整底稿：没开大模型时也能跑，开了大模型就在这份底稿上重写
        plan.aligns = align(dataset, lesson);
        Set<String> labels = new LinkedHashSet<>();
        for (BridgeDataset.Align align : plan.aligns) labels.add(align.label);

        BridgeDataset.Table primary = pickPrimary(dataset, plan.aligns);
        Map<String, String> reps = tableRep(dataset, plan.aligns, primary);
        plan.sources = buildSources(dataset, primary, reps);
        plan.joinKey = joinKeyOf(primary, plan.aligns, dataset);
        plan.joinLabel = plan.joinKey == null ? "" : labelOfField(plan.joinKey);
        plan.computes = buildComputes(dataset, plan.aligns, text, labels);
        plan.filters = buildFilters(dataset, plan.aligns, text, labels, plan);
        plan.anomalyChecks = buildAnomalyChecks(plan, labels, text, userText);
        plan.outputColumns = buildOutputColumns(plan, labels, text);
        plan.notes.addAll(duplicateKindNotes(dataset, reps));
        plan.notes.addAll(missingNotes(text, plan.aligns));
        applyLesson(plan, lesson, dataset, labels);
        run.event(dataset.id, "plan", "align_fields", "字段对齐：" + plan.aligns.size() + " 个列名 → "
                + labels.size() + " 个标准字段", crossSystemHint(dataset, plan.aligns));

        // 规划器：让模型按业务目标重新拆子任务、定参数；模型不可用就保留规则底稿
        ChatModel model = chat.getIfAvailable();
        boolean modelPlanned = false;
        if (model != null) {
            try {
                String answer = askModel(dataset.id, plannerPrompt(dataset, text, plan, labels, lesson));
                modelPlanned = applyModelPlan(plan, answer, labels, dataset);
                if (!modelPlanned) plan.notes.add("大模型返回的任务计划不完整，已保留规则方案");
            } catch (Exception e) {
                plan.notes.add("大模型规划没成功，已用规则方案：" + e.getMessage());
            }
        } else {
            plan.notes.add("当前没有启用大模型：子任务由内置规划规则拆解，可在配置里打开 app.llm.enabled");
        }

        plan.mode = modelPlanned ? "llm" : "rules";
        if (modelPlanned) {
            // 模型判定这次不用跨表时，就真的只留一张表：
            // 否则方案里写着“不关联”，执行时却把别的表悄悄并进来，行数对不上
            boolean joinPlanned = plan.steps.stream().anyMatch(s -> "join_tables".equals(s.tool));
            if (!joinPlanned) {
                BridgeDataset.Table solo = soloTable(dataset, plan);
                if (solo != null) {
                    BridgeDataset.Source source = new BridgeDataset.Source();
                    source.tableId = solo.id;
                    source.role = "主表";
                    source.reason = "按指令只处理这一张表，不跨表关联";
                    plan.sources = new ArrayList<>(List.of(source));
                    plan.joinKey = null;
                    plan.joinLabel = "";
                    plan.notes.add("按指令只处理 " + solo.file + "，其余表这次不参与");
                }
            }
            pruneSteps(plan);
            ensureExportStep(plan);
        } else {
            buildRuleSteps(dataset, plan, start);
        }

        // 异常检查和最终参与的表对一遍：没参与的表里没有的字段，检查就撤掉，
        // 否则“入职日期为空”会把人人都判成异常（薪资表根本没这一列）
        alignChecksToTables(plan);

        plan.anomalyRules = plan.anomalyChecks.stream()
                .map(check -> check.reason)
                .filter(reason -> reason != null && !reason.isBlank())
                .distinct().toList();
        plan.summary = plan.summary == null || plan.summary.isBlank() ? autoSummary(dataset, plan) : plan.summary;
        plan.goal = text;

        dataset.plan = plan;
        dataset.status = "planned";
        store.save(dataset);
        run.event(dataset.id, "plan", "planner", "方案已定：" + plan.steps.size() + " 步", plan.summary);
        log.info("桥接数据集 {} 规划完成：{} 张表，{} 个对齐字段，{} 步，模式 {}",
                dataset.id, dataset.tables.size(), plan.aligns.size(), plan.steps.size(), plan.mode);
        return dataset;
    }

    // 没开大模型时，按方案的实际内容拼子任务：单表不写关联、没有计算就不写计算，步骤表只列真正会做的事
    private void buildRuleSteps(BridgeDataset dataset, BridgeDataset.Plan plan, long start) {
        int totalColumns = dataset.tables.stream().mapToInt(t -> t.columns.size()).sum();
        plan.steps.add(step("scan_dataset", "扫描数据集",
                "遍历数据集目录，找到 " + dataset.tables.size() + " 张表、" + totalColumns + " 个字段",
                System.currentTimeMillis() - start));
        plan.steps.add(step("parse_sheets", "解析表格",
                "读表头、行数和样例值（" + totalColumns + " 列），表数据缓存在数据集目录里", 0));
        plan.steps.add(step("detect_kind", "识别业务表", kindSummary(dataset), 0));

        long distinctLabels = plan.aligns.stream().map(a -> a.label).distinct().count();
        plan.steps.add(step("align_fields", "字段语义对齐",
                "把 " + plan.aligns.size() + " 个列名对到 " + distinctLabels + " 个标准字段，"
                        + crossSystemHint(dataset, plan.aligns), 0));

        if (plan.joinKey != null) {
            BridgeDataset.Table main = plan.sources.stream()
                    .filter(s -> "主表".equals(s.role))
                    .map(s -> byId(dataset, s.tableId))
                    .filter(t -> t != null)
                    .findFirst().orElse(null);
            plan.steps.add(step("join_tables", "跨表关联",
                    "以 " + (main == null ? "主表" : main.file) + " 为主表，按「" + plan.joinLabel
                            + "」左连接其他表", 0));
        }
        if (hasRealFilters(plan)) {
            plan.steps.add(step("filter_rows", "条件筛选", String.join("；", plan.filters), 0));
        }
        if (!plan.computes.isEmpty()) {
            plan.steps.add(step("compute", "业务计算",
                    "生成 " + plan.computes.size() + " 个计算列：" + String.join("、",
                    plan.computes.stream().map(c -> c.name).toList()), 0));
        }
        plan.steps.add(step("isolate_anomaly", "异常隔离",
                plan.anomalyChecks.size() + " 条检查规则，命中就进异常清单，不影响正常行", 0));
        plan.steps.add(step("standardize_export", "标准化导出",
                "按目标系统习惯输出 " + plan.outputColumns.size() + " 列标准表 + 异常清单", 0));
    }

    // 这次不跨表时，选哪一张：能覆盖计算列最多，其次行数最多
    private BridgeDataset.Table soloTable(BridgeDataset dataset, BridgeDataset.Plan plan) {
        Set<String> needed = new LinkedHashSet<>();
        for (BridgeDataset.Compute compute : plan.computes) {
            needed.addAll(compute.plus);
            needed.addAll(compute.minus);
        }
        BridgeDataset.Table best = null;
        int bestHit = -1;
        for (BridgeDataset.Table table : dataset.tables) {
            int hit = needed.isEmpty() ? 0 : (int) plan.aligns.stream()
                    .filter(a -> a.tableId.equals(table.id) && needed.contains(a.label)).count();
            if (hit > bestHit || (hit == bestHit && best != null && table.rows > best.rows)) {
                best = table;
                bestHit = hit;
            }
        }
        return best;
    }

    private boolean hasRealFilters(BridgeDataset.Plan plan) {
        return plan.filters.stream().anyMatch(text -> !text.contains("不加额外筛选"));
    }

    // 跨表桥接的核心风险必须始终被隔离：模型漏写关联检查时补回来，不能为了达标把它藏掉
    private void ensureJoinCheck(BridgeDataset.Plan plan) {
        if (plan.joinKey == null) return;
        boolean has = plan.anomalyChecks.stream().anyMatch(check -> "join".equals(check.type));
        if (has) return;
        plan.anomalyChecks.add(0, new BridgeDataset.AnomalyCheck("join", plan.joinLabel,
                "关联键为空，或在主数据表里对不上"));
    }

    // 异常检查只对“真的参与计算”的字段生效：没被用到的列不查，免得满屏无关提醒
    private void alignChecksToTables(BridgeDataset.Plan plan) {
        Set<String> activeTables = new LinkedHashSet<>();
        for (BridgeDataset.Source source : plan.sources) {
            if (!"备选表".equals(source.role)) activeTables.add(source.tableId);
        }
        Set<String> activeLabels = new LinkedHashSet<>();
        for (BridgeDataset.Align align : plan.aligns) {
            if (activeTables.contains(align.tableId)) activeLabels.add(align.label);
        }

        List<String> dropped = new ArrayList<>();
        plan.anomalyChecks.removeIf(check -> {
            boolean missing = check.label != null && !check.label.isBlank() && !activeLabels.contains(check.label);
            if (missing) dropped.add(check.label);
            return missing;
        });
        // 不跨表时没有关联键，join 检查就是一条空跑项，界面上别挂着
        if (plan.joinKey == null) plan.anomalyChecks.removeIf(check -> "join".equals(check.type));
        for (String label : dropped) {
            plan.notes.add("「" + label + "」这条异常检查这次用不上：参与计算的表里没有这一列");
        }
        if (plan.dateField != null && !activeLabels.contains(plan.dateField)) {
            plan.dateField = null;
            plan.dateFrom = null;
            plan.dateTo = null;
        }

        // 参与加减的数值列必须有一条非数字检查，否则“面议”“--”会算成空值却当成正常行
        Set<String> guarded = new LinkedHashSet<>();
        for (BridgeDataset.AnomalyCheck check : plan.anomalyChecks) {
            if ("numeric".equals(check.type)) guarded.add(check.label);
        }
        for (BridgeDataset.Compute compute : plan.computes) {
            for (String label : compute.plus) addNumericCheck(plan, guarded, activeLabels, label);
            for (String label : compute.minus) addNumericCheck(plan, guarded, activeLabels, label);
        }

        for (BridgeDataset.Step step : plan.steps) {
            if (!"isolate_anomaly".equals(step.tool)) continue;
            step.title = "异常隔离";
            step.detail = plan.anomalyChecks.isEmpty() ? "这次没有需要单独隔离的检查项"
                    : "跑 " + plan.anomalyChecks.size() + " 条检查："
                    + String.join("、", plan.anomalyChecks.stream().map(c -> c.reason).distinct().toList());
        }
    }

    private void addNumericCheck(BridgeDataset.Plan plan, Set<String> guarded, Set<String> activeLabels, String label) {
        if (guarded.contains(label) || !activeLabels.contains(label)) return;
        if (!STD_NUMERIC.contains(fieldOfLabel(label))) return;
        plan.anomalyChecks.add(new BridgeDataset.AnomalyCheck("numeric", label, label + "出现非数字内容"));
        guarded.add(label);
    }

    // 模型给的步骤里如果没写最后一步导出，补上——自主模式规划完就直接跑，不需要人再点确认
    private void ensureExportStep(BridgeDataset.Plan plan) {
        boolean hasExport = plan.steps.stream().anyMatch(s -> "standardize_export".equals(s.tool));
        if (!hasExport) {
            plan.steps.add(step("standardize_export", "标准化导出",
                    "按目标系统习惯输出 " + plan.outputColumns.size() + " 列标准表 + 异常清单", 0));
        }
    }

    // 步骤表要跟最终方案对得上：模型排了“筛选在职员工”，但这条筛选没被采纳时，对应那一步也从轨迹里划掉
    private void pruneSteps(BridgeDataset.Plan plan) {
        Set<String> unused = new LinkedHashSet<>();
        if (plan.computes.isEmpty()) unused.add("compute");
        if (!hasRealFilters(plan) && plan.statusFilter == null && plan.dateField == null) unused.add("filter_rows");
        if (plan.joinKey == null) unused.add("join_tables");
        plan.steps.removeIf(step -> unused.contains(step.tool));
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

    // 字段对齐：自定义规则 > 内置词典 > 经验记忆。经验只补前两者都没认出来的列，免得旧的覆盖用户手填的
    private List<BridgeDataset.Align> align(BridgeDataset dataset, BridgeMemory.Lesson lesson) {
        Map<String, String> custom = customRuleMap();
        Map<String, String> byKind = new LinkedHashMap<>();
        Map<String, String> byColumn = new LinkedHashMap<>();
        if (lesson != null && lesson.aligns != null) {
            for (BridgeMemory.Alias alias : lesson.aligns) {
                if (alias == null || alias.field == null || TextUtils.isBlank(alias.column)) continue;
                String column = TextUtils.norm(alias.column);
                if (!TextUtils.isBlank(alias.kind)) {
                    byKind.putIfAbsent(TextUtils.norm(alias.kind) + "|" + column, alias.field);
                }
                byColumn.putIfAbsent(column, alias.field);
            }
        }
        List<BridgeDataset.Align> aligns = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            Map<String, BridgeDataset.Align> best = new LinkedHashMap<>();
            String kind = TextUtils.norm(table.kind == null ? "unknown" : table.kind);
            for (String column : table.columns) {
                BridgeDataset.Align align = matchField(table, column, custom);
                if (align == null) {
                    String field = byKind.get(kind + "|" + TextUtils.norm(column));
                    if (field == null) field = byColumn.get(TextUtils.norm(column));
                    if (field != null) align = align(field, table.id, column, "经验", 0.9);
                }
                if (align == null) continue;
                BridgeDataset.Align old = best.get(align.field);
                if (old == null || align.confidence > old.confidence) best.put(align.field, align);
            }
            aligns.addAll(best.values());
        }
        return aligns;
    }

    // ---------------- 经验记忆：检索、复用、沉淀 ----------------

    // 数据集里所有列名，用来算和历史的列重合度
    private List<String> allColumns(BridgeDataset dataset) {
        List<String> columns = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            for (String column : table.columns) {
                if (!columns.contains(column)) columns.add(column);
            }
        }
        return columns;
    }

    // 表类型组合，例如 erp_salary+hr_employee+oa_leave，同类数据集应当一致
    private String kindSignature(BridgeDataset dataset) {
        return dataset.tables.stream()
                .map(table -> table.kind == null ? "unknown" : table.kind)
                .distinct().sorted()
                .reduce((left, right) -> left + "+" + right).orElse("");
    }

    // 业务目标归一化：同一句指令每月跑一次，就能命中同一条经验
    private String lessonScene(BridgeDataset dataset) {
        return TextUtils.norm(dataset.instruction == null ? "" : dataset.instruction);
    }

    // 把经验落到方案上：规则没算出关联键时用上次的；业务目标完全一致时，就当上次同款来跑
    private void applyLesson(BridgeDataset.Plan plan, BridgeMemory.Lesson lesson, BridgeDataset dataset,
                            Set<String> labels) {
        if (lesson == null) return;
        int reused = 0;
        if (plan.joinKey == null && !TextUtils.isBlank(lesson.joinKey)
                && joinable(dataset, plan.aligns, lesson.joinKey)) {
            plan.joinKey = lesson.joinKey;
            plan.joinLabel = labelOfField(lesson.joinKey);
            reused++;
        }
        long fromLesson = plan.aligns.stream().filter(a -> "经验".equals(a.origin)).count();
        boolean sameScene = !TextUtils.isBlank(lesson.scene) && lesson.scene.equals(lessonScene(dataset));
        if (sameScene && plan.computes.isEmpty() && lesson.computes != null) {
            for (String text : lesson.computes) {
                BridgeDataset.Compute compute = computeOf(text, labels);
                if (compute == null) continue;
                boolean exists = plan.computes.stream().anyMatch(item -> item.name.equals(compute.name));
                if (!exists) {
                    plan.computes.add(compute);
                    reused++;
                }
            }
        }
        if (sameScene && lesson.outputColumns != null && !lesson.outputColumns.isEmpty()) {
            Set<String> allowed = new LinkedHashSet<>(labels);
            for (BridgeDataset.Compute compute : plan.computes) allowed.add(compute.name);
            allowed.add("数据来源");
            List<String> columns = lesson.outputColumns.stream().filter(allowed::contains).toList();
            if (!columns.isEmpty()) {
                plan.outputColumns = new ArrayList<>(columns);
                reused++;
            }
        }
        if (fromLesson > 0 || reused > 0) {
            plan.notes.add("经验记忆：复用 " + fromLesson + " 处历史对齐"
                    + (reused > 0 ? "、" + reused + " 处关联/计算/输出配置" : "")
                    + "（来自「" + lesson.datasetName + "」），执行前请核对一遍");
        }
    }

    // 「应发工资=基本工资+绩效-扣款」→ 计算列；字段必须当前数据集里都有
    private BridgeDataset.Compute computeOf(String text, Set<String> labels) {
        if (TextUtils.isBlank(text)) return null;
        int eq = text.indexOf('=');
        if (eq <= 0) return null;
        BridgeDataset.Compute compute = new BridgeDataset.Compute();
        compute.name = text.substring(0, eq).trim();
        compute.origin = "经验";
        List<String> plus = new ArrayList<>();
        List<String> minus = new ArrayList<>();
        for (String token : text.substring(eq + 1).split("\\+")) {
            String item = token.trim();
            if (item.isEmpty()) continue;
            if (item.startsWith("-")) {
                String label = item.substring(1).trim();
                if (labels.contains(label)) minus.add(label);
            } else if (labels.contains(item)) {
                plus.add(item);
            }
        }
        if (compute.name.isEmpty() || plus.isEmpty()) return null;
        compute.plus = plus;
        compute.minus = minus;
        return compute;
    }

    private String computeText(BridgeDataset.Compute compute) {
        StringBuilder sb = new StringBuilder(compute.name).append('=');
        sb.append(String.join("+", compute.plus));
        for (String label : compute.minus) {
            sb.append('-').append(label);
        }
        return sb.toString();
    }

    // 跑成功之后沉淀一条经验：记的是“这张表的这列其实是这个标准字段”，下次同一张表能直接认出来
    private void remember(BridgeDataset dataset, BridgeDataset.Plan plan, BridgeDataset.Result result) {
        if (result == null || result.okRows <= 0) return;
        List<BridgeMemory.Alias> aliases = new ArrayList<>();
        for (BridgeDataset.Align align : plan.aligns) {
            BridgeDataset.Table table = byId(dataset, align.tableId);
            aliases.add(new BridgeMemory.Alias(
                    table == null ? "unknown" : table.kind, align.column, align.field));
        }
        List<String> computes = new ArrayList<>();
        for (BridgeDataset.Compute compute : plan.computes) {
            computes.add(computeText(compute));
        }
        double anomalyRate = result.totalRows <= 0 ? 0 : result.anomalyRows * 1d / result.totalRows;
        BridgeMemory.Lesson lesson = memory.learn(lessonScene(dataset), dataset.name, kindSignature(dataset),
                plan.joinKey, allColumns(dataset), aliases, computes, plan.outputColumns, anomalyRate,
                plan.goal, plan.attempts.size(), result.passed);
        if (lesson == null) return;

        String detail = "已存入经验记忆：对齐 " + aliases.size() + " 列"
                + (plan.joinKey == null ? "" : "、关联键「" + plan.joinLabel + "」")
                + (computes.isEmpty() ? "" : "、计算 " + computes.size() + " 列")
                + "；目标「" + (plan.goal == null ? "" : plan.goal) + "」"
                + (result.passed ? "已达标" : "未达标")
                + (anomalyRate >= 0.25 ? "；异常占比 " + Math.round(anomalyRate * 100)
                        + "%，可信度已打折，可在经验记忆里标记「不适用」" : "")
                + "；下次同类数据集会自动复用";
        // 已经有自主循环记录时，把沉淀结果接在后面，别把循环结论覆盖掉
        BridgeDataset.Step exists = plan.steps.stream()
                .filter(step -> "autonomy".equals(step.tool) || "autonomy_note".equals(step.tool))
                .findFirst().orElse(null);
        if (exists != null) {
            exists.detail = exists.detail + "；" + detail;
            exists.state = "done";
            return;
        }
        BridgeDataset.Step step = step("autonomy_note", "记住这次配置", detail, 0);
        step.state = "done";
        plan.steps.add(step);
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

    // 「2月已享年假天数」「剩余年假可休」这类带修饰词的列，名字里虽然有“天/小时”，但算的是余额不是本次时长
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
            int score = (hasKey ? 2 : 0) + masterWeight(table.kind);
            int bestScore = (bestKey ? 2 : 0) + masterWeight(best.kind);
            if (score > bestScore || (score == bestScore && table.rows > best.rows)) best = table;
        }
        if (best == null && !dataset.tables.isEmpty()) {
            best = dataset.tables.stream().max(Comparator.comparingInt(t -> t.rows)).orElse(dataset.tables.get(0));
        }
        return best;
    }

    // 主表该选谁：一人一行的名册类表优先，流水表（多次加班、多次请假）只能当关联表。
    private int masterWeight(String kind) {
        if (kind == null) return 0;
        return switch (kind) {
            case "hr_employee" -> 3;
            case "hr_annual" -> 2;
            case "erp_salary" -> 1;
            default -> 0;
        };
    }

    private boolean hasField(List<BridgeDataset.Align> aligns, String tableId, String field) {
        return aligns.stream().anyMatch(a -> a.tableId.equals(tableId) && a.field.equals(field));
    }

    // 同一类业务表常常不止一张：OA 导出的请假单，和 HR 系统用来导入的请假模板，其实是同一批记录。
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

    // 指令里点名要的表，数据集里可能压根没有。先说清楚，别等跑完才让人发现少了一张表
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

    // 异常检查清单：每条都能在执行阶段真正跑起来，不是只写在界面上给人看。
    private List<BridgeDataset.AnomalyCheck> buildAnomalyChecks(BridgeDataset.Plan plan, Set<String> labels,
                                                                String text, String userText) {
        List<BridgeDataset.AnomalyCheck> checks = new ArrayList<>();
        if (plan.joinKey != null) {
            checks.add(new BridgeDataset.AnomalyCheck("join", "", "关联键为空，或在主数据表里对不上"));
        }
        if (labels.contains("入职日期")) {
            checks.add(new BridgeDataset.AnomalyCheck("date", "入职日期", "入职日期无法识别"));
            if (text.contains("入职") || text.contains("缺失") || text.contains("花名册")) {
                checks.add(new BridgeDataset.AnomalyCheck("blank", "入职日期", "入职信息缺失"));
            }
        }
        for (String label : List.of("工号", "姓名")) {
            if (labels.contains(label)) {
                checks.add(new BridgeDataset.AnomalyCheck("garbled", label, label + "疑似乱码"));
            }
        }
        // 参与计算的数值列一旦出现“面议”“--”，这一行的计算结果就不可信，单独隔离
        Set<String> numericLabels = new LinkedHashSet<>();
        for (BridgeDataset.Compute compute : plan.computes) {
            for (String label : compute.plus) {
                if (labels.contains(label) && STD_NUMERIC.contains(fieldOfLabel(label))) numericLabels.add(label);
            }
            for (String label : compute.minus) {
                if (labels.contains(label) && STD_NUMERIC.contains(fieldOfLabel(label))) numericLabels.add(label);
            }
        }
        for (String label : numericLabels) {
            checks.add(new BridgeDataset.AnomalyCheck("numeric", label, label + "出现非数字内容"));
        }
        // 查重只看用户自己写的那句话：同一张流水表里一人多条是正常的（多次加班、多次请假），
        // 不该因为目标是自动推断的就默认把所有重复都判成异常
        if (plan.joinKey != null && userText != null
                && (userText.contains("重复") || userText.contains("查重"))) {
            checks.add(new BridgeDataset.AnomalyCheck("duplicate", plan.joinLabel, "同一" + plan.joinLabel + "重复"));
        }
        return checks;
    }

    private String fieldOfLabel(String label) {
        return STD.stream().filter(std -> std.label().equals(label)).map(Std::key).findFirst().orElse("");
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

    // 3. 规划器（大模型）

    // 规划提示：把可用的原子能力、数据集结构、已经对齐的标准字段都摊开，让模型只做取舍
    private String plannerPrompt(BridgeDataset dataset, String instruction, BridgeDataset.Plan plan,
                                 Set<String> labels, BridgeMemory.Lesson lesson) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是跨系统表格桥接智能体的规划器。只输出一个 JSON 对象，不要解释、不要代码块标记。\n\n");
        sb.append("可用工具（steps[].tool 只能取这些名字）：\n");
        for (Map.Entry<String, String> entry : TOOL_CATALOG.entrySet()) {
            sb.append("- ").append(entry.getKey()).append("：").append(entry.getValue()).append("\n");
        }
        sb.append("\n用户业务目标：").append(instruction.isEmpty() ? "（未填写，按数据结构做标准整理）" : instruction)
                .append("\n\n数据集里的表：\n");
        for (BridgeDataset.Table table : dataset.tables) {
            sb.append("- ").append(table.id).append(" ").append(table.file)
                    .append("（").append(table.kindLabel).append("，").append(table.rows).append(" 行）")
                    .append(" 列：").append(joinLimited(table.columns, "/", 30)).append("\n");
        }
        sb.append("\n规则初稿：关联键=").append(plan.joinKey == null ? "无" : plan.joinLabel)
                .append("；跨系统对齐=").append(String.join("，", plan.aligns.stream()
                        .map(a -> a.column + "→" + a.label).distinct().limit(12).toList()))
                .append("\n");
        // 历史经验：上次处理同类数据集、用户确认并执行成功过的方案，给模型当参照，
        // 让同一类报表每月的结果口径保持一致；和本次目标冲突时以本次目标为准
        if (lesson != null) {
            sb.append("\n历史经验（同类数据集「").append(lesson.datasetName).append("」上次执行成功的配置，")
                    .append("可参考，但与本次业务目标冲突时以本次目标为准）：\n");
            List<String> aliases = lesson.aligns == null ? List.of() : lesson.aligns.stream()
                    .filter(a -> a.column != null && a.field != null)
                    .map(a -> a.column + "→" + labelOfField(a.field)).distinct().limit(12).toList();
            if (!aliases.isEmpty()) sb.append("- 对齐：").append(String.join("，", aliases)).append("\n");
            if (!TextUtils.isBlank(lesson.joinKey)) {
                sb.append("- 关联键：").append(labelOfField(lesson.joinKey)).append("\n");
            }
            if (lesson.computes != null && !lesson.computes.isEmpty()) {
                sb.append("- 计算列：").append(joinLimited(lesson.computes, "，", 8)).append("\n");
            }
            if (lesson.outputColumns != null && !lesson.outputColumns.isEmpty()) {
                sb.append("- 输出列：").append(joinLimited(lesson.outputColumns, "、", 10)).append("\n");
            }
        }
        sb.append("\n可用的标准字段（计算、筛选、异常、输出列只能用这些中文名，且必须是数据集里已对齐的）：\n")
                .append(joinLimited(labels, "、", 40)).append("\n");
        // 用户上传的知识库：制度里的口径（比如"入职当月不计绩效"）只能靠它，模型自己编不出来
        sb.append(knowledgeBlock(instruction + " " + dataset.name + " "
                + String.join(" ", dataset.tables.stream().map(t -> t.kindLabel).toList())));
        sb.append("\n输出 JSON 结构：\n");
        sb.append("{\"summary\":\"一句话方案\",")
                .append("\"steps\":[{\"tool\":\"scan_dataset\",\"title\":\"扫描数据集\",\"detail\":\"这一步做了什么\"}],")
                .append("\"join_key\":\"emp_no 或 name 或空\",")
                .append("\"compute\":[{\"name\":\"应发工资\",\"plus\":[\"基本工资\",\"绩效\",\"补贴\"],\"minus\":[\"扣款\"]}],")
                .append("\"filters\":[\"只保留在职员工\"],")
                .append("\"date_field\":\"入职日期或空\",\"date_from\":\"2024-01-01 或空\",\"date_to\":\"2024-12-31 或空\",")
                .append("\"status\":\"在职 或 离职 或 空\",")
                .append("\"anomaly\":[{\"type\":\"blank\",\"field\":\"入职日期\",\"reason\":\"入职信息缺失\"}],")
                .append("\"output_columns\":[\"工号\",\"姓名\",\"应发工资\"],")
                .append("\"notes\":[\"需要提醒用户的话\"]}\n");
        sb.append("\n要求：\n")
                .append("1. steps 按真实执行顺序写，3 到 9 步，最后一步必须是 standardize_export，detail 写清这一步做什么。\n")
                .append("2. 只排这个业务需要的步骤：只有一张表就不要 join_tables，没有要算的列就不要 compute。\n")
                .append("3. compute 的 plus/minus、anomaly.field、output_columns 只能用上面列出的标准字段名；数据集里没有的不要编。\n")
                .append("4. anomaly.type 只能是 join / blank / date / numeric / garbled / duplicate；join 不用填 field。\n")
                .append("5. filters、status、date_from、date_to 只能来自用户在指令里明确说过的条件："
                        + "用户没说“在职/离职”就不要填 status，没给日期区间就不要填 date_from/date_to。\n")
                .append("6. 用户没提的计算不要加，拿不准就把对应字段留空，不要自己补充业务规则。\n");
        return sb.toString();
    }

    // 把模型的计划落到方案上；返回 true 表示这次规划确实用上了模型
    private boolean applyModelPlan(BridgeDataset.Plan plan, String answer, Set<String> labels,
                                   BridgeDataset dataset) {
        JsonNode node = parseJson(answer);
        if (node == null) {
            plan.notes.add("大模型返回的内容不是 JSON，已忽略");
            return false;
        }
        return applyModelPlan(plan, node, labels, dataset);
    }

    // 已经解析成 JSON 的场景（执行后的自我评估）直接用这个重载，少解析一遍
    private boolean applyModelPlan(BridgeDataset.Plan plan, JsonNode node, Set<String> labels,
                                   BridgeDataset dataset) {
        boolean changed = false;

        // 1. 子任务清单：只认白名单里的工具，不足 3 步就当模型没规划
        List<BridgeDataset.Step> steps = new ArrayList<>();
        for (JsonNode item : node.path("steps")) {
            String tool = item.path("tool").asText("");
            if (!TOOL_CATALOG.containsKey(tool)) continue;
            BridgeDataset.Step step = new BridgeDataset.Step();
            step.tool = tool;
            step.title = blankTo(item.path("title").asText(""), TOOL_TITLES.get(tool));
            step.detail = blankTo(item.path("detail").asText(""), TOOL_CATALOG.get(tool));
            step.state = "done";
            steps.add(step);
        }
        if (steps.size() >= 3) {
            plan.steps = steps;
            changed = true;
        }

        // 2. 关联键：必须真的在两张以上的表里都对齐了，否则不认
        String joinText = node.path("join_key").asText("");
        String candidate = joinText.contains("name") || joinText.contains("姓名") ? "name"
                : joinText.contains("emp") || joinText.contains("工号") ? "emp_no" : null;
        if (candidate != null && joinable(dataset, plan.aligns, candidate)) {
            plan.joinKey = candidate;
            plan.joinLabel = labelOfField(candidate);
            changed = true;
        }

        // 3. 计算列
        List<BridgeDataset.Compute> computes = new ArrayList<>();
        for (JsonNode item : node.path("compute")) {
            BridgeDataset.Compute compute = new BridgeDataset.Compute();
            compute.name = item.path("name").asText("");
            compute.plus = validLabels(item.path("plus"), labels);
            compute.minus = validLabels(item.path("minus"), labels);
            compute.origin = "AI";
            if (!compute.name.isBlank() && !compute.plus.isEmpty()) computes.add(compute);
        }
        if (!computes.isEmpty()) {
            plan.computes = computes;
            changed = true;
        }

        // 4. 筛选条件
        if (applyModelFilters(plan, node, labels, dataset.instruction)) changed = true;

        // 5. 异常检查：只认白名单类型，字段必须是已对齐的标准字段
        List<BridgeDataset.AnomalyCheck> checks = new ArrayList<>();
        for (JsonNode item : node.path("anomaly")) {
            String type = item.path("type").asText("");
            if (!ANOMALY_TYPES.contains(type)) continue;
            String field = item.path("field").asText("");
            String label = labels.contains(field) ? field : ("join".equals(type) ? "" : plan.joinLabel);
            String reason = blankTo(item.path("reason").asText(""), defaultReason(type, label));
            checks.add(new BridgeDataset.AnomalyCheck(type, label, reason));
        }
        if (!checks.isEmpty()) {
            // 有跨表关联就必须保留 join 检查，否则“对不上主表”的行会被当成正常数据
            if (plan.joinKey != null && checks.stream().noneMatch(c -> "join".equals(c.type))) {
                checks.add(0, new BridgeDataset.AnomalyCheck("join", "", "关联键为空，或在主数据表里对不上"));
            }
            plan.anomalyChecks = checks;
            changed = true;
        }

        // 6. 输出列
        Set<String> computeNames = new LinkedHashSet<>();
        for (BridgeDataset.Compute compute : plan.computes) computeNames.add(compute.name);
        List<String> columns = new ArrayList<>();
        for (JsonNode item : node.path("output_columns")) {
            String column = item.asText("");
            boolean valid = labels.contains(column) || computeNames.contains(column) || "数据来源".equals(column);
            if (valid && !columns.contains(column)) columns.add(column);
        }
        if (!columns.isEmpty()) {
            plan.outputColumns = columns;
            changed = true;
        }

        String summary = node.path("summary").asText("");
        // 模型爱在概述里强调“剔除非在职人员”，但这条筛选没被采纳时，概述就成了假话
        if (summary.length() > 4 && !mentionsUnaskedStatus(summary, dataset.instruction)) {
            plan.summary = summary;
            changed = true;
        }
        // 7. 主表：主从关系认反是跨表对齐最常见的错，模型说换主表就按它换
        String primaryFile = node.path("primary_table").asText("");
        if (!primaryFile.isBlank() && reassignPrimary(plan, dataset, primaryFile)) changed = true;

        for (JsonNode item : node.path("notes")) {
            String note = item.asText("");
            if (note.isBlank() || mentionsUnaskedStatus(note, dataset.instruction)) continue;
            plan.notes.add("大模型提示：" + note);
        }
        // 模型改了异常检查或输出列之后，检查项要跟最终参与的表重新对齐（含补回缺失的关联检查）
        ensureJoinCheck(plan);
        alignChecksToTables(plan);
        return changed;
    }

    // 换主表：主表决定“一人一行”的输出骨架，认反了会整表对不上。
    private boolean reassignPrimary(BridgeDataset.Plan plan, BridgeDataset dataset, String primaryFile) {
        BridgeDataset.Table target = dataset.tables.stream()
                .filter(t -> t.file != null
                        && (t.file.equals(primaryFile) || t.file.contains(primaryFile)
                        || primaryFile.contains(t.file)))
                .findFirst().orElse(null);
        if (target == null || plan.sources.isEmpty()) return false;
        boolean already = plan.sources.stream()
                .anyMatch(s -> "主表".equals(s.role) && s.tableId.equals(target.id));
        if (already) return false;
        for (BridgeDataset.Source source : plan.sources) {
            if (source.tableId.equals(target.id)) {
                source.role = "主表";
                continue;
            }
            BridgeDataset.Table table = byId(dataset, source.tableId);
            source.role = table != null && target.kind != null && target.kind.equals(table.kind)
                    ? "备选表" : "关联表";
        }
        plan.notes.add("自我评估：主表改为「" + target.file + "」后重跑");
        return true;
    }

    // 概述或提示里提到了在职/离职，而用户根本没提这个条件
    private boolean mentionsUnaskedStatus(String text, String instruction) {
        boolean asked = instruction.contains("在职") || instruction.contains("离职");
        return !asked && (text.contains("在职") || text.contains("离职"));
    }

    // 模型给的日期区间和在职状态要翻译成执行阶段真正能用的筛选。
    private boolean applyModelFilters(BridgeDataset.Plan plan, JsonNode node, Set<String> labels,
                                      String instruction) {
        boolean changed = false;
        boolean askedStatus = instruction.contains("在职") || instruction.contains("离职");
        boolean askedDate = instruction.matches("(?s).*\\d{4}.*") || instruction.contains("至今")
                || instruction.contains("本月") || instruction.contains("上月") || instruction.contains("今年");
        List<String> dropped = new ArrayList<>();

        List<String> filters = new ArrayList<>();
        for (JsonNode item : node.path("filters")) {
            String text = item.asText("").trim();
            if (text.isEmpty() || filters.size() >= 6) continue;
            if (!askedStatus && (text.contains("在职") || text.contains("离职"))) {
                dropped.add(text);
                continue;
            }
            if (!askedDate && (text.contains("日期") || text.contains("期间"))) {
                dropped.add(text);
                continue;
            }
            filters.add(text);
        }
        if (!filters.isEmpty()) {
            plan.filters = filters;
            changed = true;
        }

        LocalDate fromDate = TextUtils.parseDate(node.path("date_from").asText(""));
        LocalDate toDate = TextUtils.parseDate(node.path("date_to").asText(""));
        if (askedDate && (fromDate != null || toDate != null)) {
            String field = node.path("date_field").asText("");
            plan.dateField = labels.contains(field) ? field
                    : List.of("入职日期", "开始日期", "加班日期").stream()
                    .filter(labels::contains).findFirst().orElse(null);
            if (plan.dateField != null) {
                plan.dateFrom = fromDate == null ? null : TextUtils.formatDate(fromDate);
                plan.dateTo = toDate == null ? null : TextUtils.formatDate(toDate);
                boolean mentioned = plan.filters.stream()
                        .anyMatch(f -> f.contains(plan.dateField) && f.contains("保留"));
                if (!mentioned) {
                    plan.filters.add("只保留" + plan.dateField + "在 "
                            + (fromDate == null ? "最早" : plan.dateFrom) + " 到 "
                            + (toDate == null ? "最新" : plan.dateTo) + " 之间的行");
                }
                changed = true;
            }
        }

        String status = node.path("status").asText("");
        if (askedStatus && (status.contains("在职") || status.contains("离职"))) {
            plan.statusFilter = status.contains("离职") ? "离职" : "在职";
            boolean mentioned = plan.filters.stream()
                    .anyMatch(f -> f.contains(plan.statusFilter) && f.contains("保留"));
            if (!mentioned) plan.filters.add("只保留" + plan.statusFilter + "状态的员工");
            changed = true;
        }
        for (String text : dropped) {
            plan.notes.add("大模型建议的筛选「" + text + "」指令里没提，这次没有执行");
        }
        return changed;
    }

    // 关联键至少要出现在两张表里，才谈得上跨表关联
    private boolean joinable(BridgeDataset dataset, List<BridgeDataset.Align> aligns, String field) {
        long tables = dataset.tables.stream()
                .filter(table -> aligns.stream().anyMatch(a -> a.tableId.equals(table.id) && a.field.equals(field)))
                .count();
        return tables >= 2;
    }

    // 这个标准字段在几张表里出现过：换关联键被拒时，用来说明原因
    private int tableCountOf(BridgeDataset dataset, List<BridgeDataset.Align> aligns, String field) {
        return (int) dataset.tables.stream()
                .filter(table -> aligns.stream().anyMatch(a -> a.tableId.equals(table.id) && a.field.equals(field)))
                .count();
    }

    private String defaultReason(String type, String label) {
        return switch (type) {
            case "join" -> "关联键为空，或在主数据表里对不上";
            case "blank" -> label + "为空（按业务要求）";
            case "date" -> label + "无法识别";
            case "numeric" -> label + "出现非数字内容";
            case "garbled" -> label + "疑似乱码";
            case "duplicate" -> "同一" + label + "重复";
            default -> "命中异常检查";
        };
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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

    // 4. 执行

    // 4. 自主执行

    // ---------------- 运行席位与重试：把“偶发失败”和“真的坏了”分开 ----------------
    // 模型调用最多试几次：首次 + 1 次重试。再失败就该走降级逻辑，不能让用户干等
    private static final int MODEL_ATTEMPTS = 2;
    // 读写表格最多试几次：文件被 Excel 占用是最常见的偶发错误，多等两拍基本都能过去
    private static final int IO_ATTEMPTS = 3;
    // 退避基数：第 n 次失败等 base * 2^(n-1)，1s / 2s 这样涨，不把模型配额打得更满
    private static final long RETRY_BASE_MS = 800;

    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    // 抢运行席位：同一单串行 + 全局限并发，排到队才真正开跑。
    private void beginRun(String id) {
        String why = run.begin(id);
        if (why != null) {
            run.finish(id);
            throw new IllegalStateException(why);
        }
    }

    // 带指数退避的重试：what 只用于日志，方便事后知道是哪个环节在反复失败
    private <T> T retry(String what, int attempts, CheckedSupplier<T> action) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (Exception e) {
                last = e;
                if (attempt >= attempts) break;
                long wait = RETRY_BASE_MS * (1L << (attempt - 1));
                log.warn("{}第 {} 次失败，{} 毫秒后重试：{}", what, attempt, wait, e.getMessage());
                if (!sleep(wait)) throw e;
            }
        }
        throw last == null ? new IllegalStateException(what + "失败") : last;
    }

    // 调一次模型：失败原地重试一次。每次真实请求都记一次预算，重试用完还失败就往上抛
    private String askModel(String id, String prompt) {
        ChatModel model = chat.getIfAvailable();
        if (model == null) throw new IllegalStateException("当前没有启用大模型");
        Exception last = null;
        for (int attempt = 1; attempt <= MODEL_ATTEMPTS; attempt++) {
            run.noteModelCall(id);
            try {
                return model.chat(prompt);
            } catch (Exception e) {
                last = e;
                if (attempt >= MODEL_ATTEMPTS) break;
                log.warn("大模型第 {} 次调用失败，{} 毫秒后重试：{}", attempt, RETRY_BASE_MS, e.getMessage());
                if (!sleep(RETRY_BASE_MS)) break;
            }
        }
        throw new IllegalStateException("大模型调用失败：" + (last == null ? "未知原因" : last.getMessage()), last);
    }

    // 退避等待；被中断就返回 false，让调用方按取消处理，不要吞掉中断标记
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // 提示词里的清单要设上限：宽表几十列、标准字段几十个，全塞进去只会吃掉上下文、拉低准头。
    private static String joinLimited(Collection<String> items, String separator, int max) {
        if (items == null || items.isEmpty()) return "";
        List<String> list = new ArrayList<>(items);
        if (list.size() <= max) return String.join(separator, list);
        return String.join(separator, list.subList(0, max)) + " 等 " + list.size() + " 项";
    }

    // 自主循环最多跑几轮：第一轮抓底，之后最多两次修正，再改就是乱试了
    private static final int MAX_AUTONOMOUS_ROUNDS = 3;
    // 默认验收线：正常行占比 90% 以上才算这一单做完
    private static final double DEFAULT_PASS_RATIO = 0.9;

    // 全自主跑一单：自己定目标（没给就按结构推断）→ 规划 → 执行 → 对照验收标准 →
    public BridgeDataset auto(String id, String instruction) {
        // 一单只允许一个运行：连点两下或者两个人同时点，都只能有一个真的在跑；
        // 全局并发满时就在队列里等，别让几个单一起抢模型配额、把每单都拖慢
        return guarded(id, () -> autoInternal(id, instruction));
    }

    // 把“跑这一单”包起来：抢席位 → 执行 → 失败也要把这一单标成失败并留下原因。
    private BridgeDataset guarded(String id, java.util.function.Supplier<BridgeDataset> body) {
        beginRun(id);
        long started = System.currentTimeMillis();
        BridgeDataset done = null;
        try {
            done = body.get();
            return done;
        } catch (RuntimeException e) {
            markFailed(id, e);
            throw e;
        } finally {
            run.finish(id);
            recordRun(done, System.currentTimeMillis() - started);
        }
    }

    // 记进运行观测：只出方案还没执行的不算一次跑单，其余按交付/失败记
    private void recordRun(BridgeDataset done, long durationMs) {
        try {
            if (done != null && "awaiting".equals(done.status)) {
                return;
            }
            AgentRunStore.Run record = new AgentRunStore.Run();
            record.at = TimeUtils.nowSecond();
            record.status = done != null && "done".equals(done.status) ? "ok" : "failed";
            record.durationMs = durationMs;
            record.files = done == null ? 0 : done.tables.size();
            BridgeDataset.Plan plan = done == null ? null : done.plan;
            if (plan != null) {
                record.mode = plan.mode == null || plan.mode.isBlank() ? "rules" : plan.mode;
                record.steps = plan.steps == null ? 0 : plan.steps.size();
                record.fixes = plan.attempts == null ? 0 : plan.attempts.size();
                record.note = clip(plan.summary);
                if (plan.steps != null) {
                    for (BridgeDataset.Step step : plan.steps) {
                        if (step.tool != null && !step.tool.isBlank() && !record.tools.contains(step.tool)) {
                            record.tools.add(step.tool);
                        }
                    }
                }
            }
            if (done != null && !"done".equals(done.status) && !done.notes.isEmpty()) {
                record.note = clip(done.notes.get(done.notes.size() - 1));
            }
            agentRuns.recordRun(record);
        } catch (Exception e) {
            log.warn("记运行观测时出错（不影响这一单的结果）：{}", e.getMessage());
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    // 失败要把状态落定，并把原因写进备注与运行日志，避免“卡在运行中”
    private void markFailed(String id, RuntimeException error) {
        String why = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        try {
            BridgeDataset dataset = store.load(id);
            if (dataset == null) return;
            // 已经交付的单（比如“问一句”时出的错）不该被倒回去标成失败
            if ("done".equals(dataset.status)) return;
            dataset.status = "failed";
            dataset.notes.add("处理失败：" + why);
            store.save(dataset);
        } catch (Exception e) {
            log.warn("标记失败状态时出错：{}", e.getMessage());
        }
        run.event(id, "done", "", "处理失败：" + why, "这一单没有交付结果，可按提示修好后重试");
    }

    private BridgeDataset autoInternal(String id, String instruction) {
        run.event(id, "plan", "scan_dataset", "读取数据集，判定每张表的业务归属", "");
        BridgeDataset planned = plan(id, instruction);
        BridgeDataset.Plan plan = planned.plan;
        plan.auto = true;
        plan.passRatio = passRatioOf(planned, plan);
        plan.acceptance = acceptanceOf(planned, plan);
        plan.attempts = new ArrayList<>();
        // 自主模式下没有“等人确认”这一步，方案一出来就直接跑
        plan.confirmed = true;

        // 手动审批：方案出完就停在 awaiting，等人点「确认执行」再动手；
        // 自动审批和完全访问都直接往下跑（区别只在 audit 上怎么记，见 accessLabel）。
        if (BridgeDataset.ACCESS_MANUAL.equals(planned.access)) {
            planned.status = "awaiting";
            planned.notes.add("按「手动审批」停在方案确认，确认后才执行导出");
            store.save(planned);
            run.event(id, "plan", "", "方案已出，等你确认后再执行", plan.summary,
                    trace("表数", planned.tables.size(), "行数", planned.tables.stream()
                            .mapToInt(table -> table.rows).sum(), "验收线", pct(plan.passRatio)));
            return planned;
        }

        run.event(id, "execute", "", "开始执行", plan.summary, trace(
                "表数", planned.tables.size(),
                "行数", planned.tables.stream().mapToInt(table -> table.rows).sum(),
                "验收线", pct(plan.passRatio),
                "权限", accessLabel(planned.access)));
        // 规划阶段就被取消了，就别再往下跑（模型规划可能要几十秒，用户等不下去是常事）
        if (run.cancelled(id)) {
            planned.status = "failed";
            planned.notes.add("已按你的取消停止，这一单没有生成结果");
            run.event(id, "done", "", "已取消，未生成结果", "");
            store.save(planned);
            return planned;
        }

        planned.status = "running";
        store.save(planned);
        autonomyLoop(planned, plan, "");
        return planned;
    }

    // 自主循环本身：执行 → 对照验收标准 → 决定改一处 → 重跑 → 没变好就回滚，最多三轮
    private BridgeDataset.Result autonomyLoop(BridgeDataset planned, BridgeDataset.Plan plan, String prefix) {
        String id = planned.id;
        BridgeDataset.Result current = execute(planned, plan, System.currentTimeMillis());
        int round = 1;
        recordAttempt(plan, round, current, "", passes(plan, current) ? "passed" : "");
        StringBuilder timeline = new StringBuilder(
                TextUtils.isBlank(prefix) ? "" : prefix + "；");
        timeline.append("第 1 轮：正常 ").append(current.okRows).append(" 行 / 异常 ")
                .append(current.anomalyRows).append(" 行（验收线 ").append(pct(plan.passRatio)).append("）");
        run.event(id, "verify", "autonomy", "第 1 轮：正常 " + current.okRows + " 行 / 异常 "
                + current.anomalyRows + " 行", "验收线 " + pct(plan.passRatio)
                + (TextUtils.isBlank(prefix) ? "" : "；" + prefix), trace(
                "轮次", 1, "正常", current.okRows, "异常", current.anomalyRows,
                "合计", current.totalRows, "通过", passes(plan, current)));

        while (round < MAX_AUTONOMOUS_ROUNDS && !passes(plan, current) && current.totalRows > 0) {
            // 用户点了取消：不再继续改方案，手里这版结果照样交付，不白跑
            if (run.cancelled(id)) {
                timeline.append("；已按你的取消停止，按当前结果交付");
                run.event(id, "done", "", "已取消：按当前结果交付", "");
                break;
            }
            // 时间/模型调用预算用完：同样停在当前最好的结果，不让一次点击无限期占着线程
            String budget = run.budgetNote(id);
            if (budget != null) {
                timeline.append("；").append(budget).append("，按当前结果交付");
                run.event(id, "verify", "autonomy", budget + "，按当前结果交付", "");
                break;
            }
            round++;
            Snapshot before = snapshot(plan);
            String decision = decide(planned, plan, current, round);
            if (decision == null) {
                lastAttempt(plan).verdict = "no_change";
                timeline.append("；复核后没有可执行的改法，按当前方案交付");
                run.event(id, "verify", "autonomy", "复核后没有可执行的改法，按当前方案交付", "");
                break;
            }
            lastAttempt(plan).decision = decision;
            run.event(id, "verify", "autonomy", "第 " + round + " 轮决定：" + decision, "",
                    trace("轮次", round, "决定", decision, "改前异常", current.anomalyRows));
            BridgeDataset.Result next = execute(planned, plan, System.currentTimeMillis());
            if (better(current, next)) {
                current = next;
                // 决定记在“做出决定的那一轮”上，新的一轮只记结果，避免同一句话出现两次
                recordAttempt(plan, round, next, "",
                        passes(plan, next) ? "passed" : "improved");
                timeline.append("；第 ").append(round).append(" 轮决定：").append(decision)
                        .append(" → 正常 ").append(next.okRows).append(" 行 / 异常 ")
                        .append(next.anomalyRows).append(" 行");
                run.event(id, "verify", "autonomy", "第 " + round + " 轮更新：正常 "
                        + next.okRows + " 行 / 异常 " + next.anomalyRows + " 行", "");
                if (passes(plan, next)) break;
            } else {
                // 改完反而没变好（或更差）：退回改之前的方案并重跑，把结果文件也换回来
                int tried = next.anomalyRows;
                before.restore(plan);
                BridgeDataset.Result back = execute(planned, plan, System.currentTimeMillis());
                recordAttempt(plan, round, back, "", "rolled_back");
                timeline.append("；第 ").append(round).append(" 轮决定：").append(decision)
                        .append(" → 异常 ").append(tried).append(" 行没有减少，已回滚到上一版方案");
                run.event(id, "verify", "autonomy", "第 " + round + " 轮：异常 " + tried
                        + " 行没有减少，已回滚", "退回上一版方案并重跑，结果文件也换回上一版");
                current = back;
                break;
            }
        }

        current.rounds = plan.attempts.size();
        current.passed = passes(plan, current);
        current.revised = plan.attempts.size() > 1;
        plan.steps.forEach(step -> step.state = "done");
        BridgeDataset.Step loop = step("autonomy", "自主循环", timeline.toString(), 0);
        loop.state = "done";
        plan.steps.add(loop);
        planned.result = current;
        planned.status = "done";
        // 导出文件同步一份到前端目录下的 downloads，页面提示里显示的就是这个位置
        mirrorArtifacts(planned, current);
        // 成功跑完就沉淀一条经验：下次同类数据集规划时先用上，越用越准；
        // 异常占比会一并记下来，供可信度打分使用
        remember(planned, plan, current);
        store.save(planned);
        run.event(id, "done", "", current.passed ? "已完成，按验收标准交付" : "已完成交付（未达标）",
                current.rounds + " 轮，正常 " + current.okRows + " 行 / 异常 " + current.anomalyRows + " 行",
                trace("轮次", current.rounds, "正常", current.okRows, "异常", current.anomalyRows,
                        "合计", current.totalRows, "耗时(ms)", current.elapsedMs,
                        "修复脏值", current.repairs, "通过", current.passed,
                        "导出目录", current.downloadDir));
        log.info("桥接数据集 {} 自主完成：{} 轮，{} 行，异常 {} 行，{}（耗时 {}ms）",
                planned.id, current.rounds, current.okRows, current.anomalyRows,
                current.passed ? "已达标" : "未达标，已按最优方案交付", current.elapsedMs);
        return current;
    }

    // 用一句话改：交付后用户说“部门列不要了”“按姓名关联”“加一列实发工资”，模型听懂就走模型，没听懂退回规则句式
    public BridgeDataset tweak(String id, String request) {
        // 改方案同样跑一轮执行，和首跑共用一把运行锁：正在跑的时候不允许再叠一单
        return guarded(id, () -> tweakInternal(id, request));
    }

    private BridgeDataset tweakInternal(String id, String request) {
        BridgeDataset dataset = store.load(id);
        if (dataset == null) {
            throw new IllegalArgumentException("数据集不存在");
        }
        String text = request == null ? "" : request.trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("先写一句想怎么改，例如「部门列不要了」「按姓名关联」");
        }
        BridgeDataset.Plan base = dataset.plan;
        // 还没跑过、或没有可改的方案：这句话就当成一次新的业务目标，直接走完整的自主流程
        if (base == null || dataset.result == null) {
            return autoInternal(id, text);
        }

        dataset.notes.add("你提出的修改：" + text);
        if (TextUtils.isBlank(base.goal)) {
            base.goal = dataset.instruction == null ? "" : dataset.instruction;
        }
        base.auto = true;
        base.confirmed = true;
        base.passRatio = base.passRatio <= 0 ? passRatioOf(dataset, base) : base.passRatio;
        if (base.acceptance.isEmpty()) base.acceptance = acceptanceOf(dataset, base);
        base.attempts = new ArrayList<>();
        base.steps.removeIf(step -> "autonomy".equals(step.tool) || "autonomy_note".equals(step.tool));

        Set<String> labels = new LinkedHashSet<>();
        for (BridgeDataset.Align align : base.aligns) labels.add(align.label);

        String how = "";
        ChatModel model = chat.getIfAvailable();
        if (model != null) {
            try {
                JsonNode node = parseJson(askModel(dataset.id, tweakPrompt(dataset, base, text)));
                if (node != null && applyModelPlan(base, node, labels, dataset)) {
                    how = "让大模型按这句话重排了方案";
                }
            } catch (Exception e) {
                dataset.notes.add("大模型没读懂这句修改，改用规则理解：" + e.getMessage());
            }
        }
        if (TextUtils.isBlank(how)) how = applyRuleTweak(base, dataset, labels, text);
        if (TextUtils.isBlank(how)) {
            // 分不清就老实说明，别假装改了：提问类的话本来就不需要动方案
            how = text.matches(".*(吗|呢|怎么样|如何|为什么|是否|是不是|多少|看看|请问|能不能|有没有).*")
                    ? "这句话像提问，不是要改方案，已按原方案重跑一遍"
                    : "没能理解这句修改，按原方案重跑一遍作为对照";
        }

        ensureJoinCheck(base);
        alignChecksToTables(base);
        base.anomalyRules = base.anomalyChecks.stream()
                .map(check -> check.reason)
                .filter(reason -> reason != null && !reason.isBlank())
                .distinct().toList();
        base.summary = TextUtils.isBlank(base.summary) ? autoSummary(dataset, base) : base.summary;

        dataset.status = "running";
        store.save(dataset);
        autonomyLoop(dataset, base, "按「" + text + "」改：" + how);
        return dataset;
    }

    // 没有大模型时的口语理解：只认几类最常说的修改，听不懂就老实说没理解，不硬猜
    private String applyRuleTweak(BridgeDataset.Plan plan, BridgeDataset dataset, Set<String> labels, String request) {
        List<String> actions = new ArrayList<>();
        String text = request.replace('\u3000', ' ').trim();

        // 1）计算式：句子里带等号，就按“X=Y+Z-W”解析（允许“帮我加一列”这类前缀）。
        // 右式里既可以写已对齐的字段，也可以引用已有的计算列（例如“税后=实发工资-扣款”）
        Set<String> allowed = new LinkedHashSet<>(labels);
        plan.computes.forEach(item -> allowed.add(item.name));
        BridgeDataset.Compute compute = computeFromText(text, allowed);
        if (compute != null) {
            plan.computes.removeIf(item -> item.name.equals(compute.name));
            plan.computes.add(compute);
            if (!plan.outputColumns.contains(compute.name)) plan.outputColumns.add(compute.name);
            actions.add("新增计算列「" + computeText(compute) + "」");
        }

        // 2）换关联键：说了“按姓名”或“按工号”，且这两个字段确实在两张以上的表里都对齐了
        if (text.contains("姓名")) {
            if (joinable(dataset, plan.aligns, "name")) {
                plan.joinKey = "name";
                plan.joinLabel = labelOfField("name");
                actions.add("关联键改为「姓名」");
            } else {
                // 只有一张表有姓名时没法按它关联，说清楚而不是假装改了
                actions.add("「姓名」只出现在 " + tableCountOf(dataset, plan.aligns, "name")
                        + " 张表里，无法作为关联键，仍按「" + (TextUtils.isBlank(plan.joinLabel) ? "工号" : plan.joinLabel) + "」关联");
            }
        } else if ((text.contains("工号") || text.contains("编号")) && joinable(dataset, plan.aligns, "emp_no")) {
            plan.joinKey = "emp_no";
            plan.joinLabel = labelOfField("emp_no");
            actions.add("关联键改为「工号」");
        }

        // 3）撤掉某条异常检查：说了“异常/检查/清单”就只动检查，别顺手把输出列也删了
        boolean aboutChecks = text.contains("异常") || text.contains("检查") || text.contains("清单")
                || text.contains("隔离") || text.contains("对不上") || text.contains("进不了");
        // 说了“列/输出/保留”就只动输出列，别动异常检查
        boolean aboutColumns = !aboutChecks && (text.contains("列") || text.contains("输出") || text.contains("保留"));
        if (aboutChecks || !aboutColumns) {
            List<BridgeDataset.AnomalyCheck> kept = new ArrayList<>();
            for (BridgeDataset.AnomalyCheck check : plan.anomalyChecks) {
                boolean remove = !"join".equals(check.type)
                        && (afterRemoval(text, check.label) || afterRemoval(text, headOf(check.reason)));
                if (remove) actions.add("撤掉异常检查「" + check.reason + "」");
                else kept.add(check);
            }
            if (kept.size() < plan.anomalyChecks.size()) {
                plan.anomalyChecks = kept;
            }
        }

        // 4）增删输出列
        if (aboutColumns || !aboutChecks) {
            for (String label : labels) {
                if (text.contains(label) && !plan.outputColumns.contains(label) && afterAdd(text, label)) {
                    plan.outputColumns.add(label);
                    actions.add("输出列加上「" + label + "」");
                }
            }
            for (String column : new ArrayList<>(plan.outputColumns)) {
                if (labels.contains(column) && afterRemoval(text, column)) {
                    plan.outputColumns.remove(column);
                    actions.add("输出列去掉「" + column + "」");
                }
            }
        }
        return actions.isEmpty() ? "" : String.join("；", actions.stream().distinct().toList());
    }

    // “实发工资 = 应发工资 - 扣款”这类句子 → 计算列；任一侧用到的字段不在当前数据集里就放弃
    private BridgeDataset.Compute computeFromText(String text, Set<String> labels) {
        // 先把「」『』、引号、括号这些外壳去掉，否则「税后=实发工资-扣款」里的右引号会被当成字段名的一部分
        String squashed = text.replaceAll("[\"'“”‘’「」『』（）()【】《》]", "").replaceAll("\\s+", "");
        if (squashed.contains("＝")) squashed = squashed.replace('＝', '=');
        int eq = squashed.indexOf('=');
        if (eq <= 0) return null;
        String name = squashed.substring(0, eq);
        for (String prefix : List.of("帮我加一列", "帮我增加", "请加一列", "新增一列", "增加一列", "添加一列",
                "帮我", "请", "再", "新增", "增加", "添加", "加上", "加一列", "计算列", "输出列", "计算", "输出", "一列", "的")) {
            while (name.length() > prefix.length() && name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
        }
        if (name.isBlank()) return null;
        List<String> plus = new ArrayList<>();
        List<String> minus = new ArrayList<>();
        String token = "";
        char sign = '+';
        String right = squashed.substring(eq + 1) + "+";
        for (int i = 0; i < right.length(); i++) {
            char ch = right.charAt(i);
            if (ch == '+' || ch == '-') {
                if (!token.isEmpty()) {
                    if (!labels.contains(token)) return null;
                    (sign == '+' ? plus : minus).add(token);
                }
                token = "";
                sign = ch;
            } else {
                token = token + ch;
            }
        }
        if (plus.isEmpty()) return null;
        BridgeDataset.Compute compute = new BridgeDataset.Compute();
        compute.name = name;
        compute.plus = plus;
        compute.minus = minus;
        compute.origin = "你的要求";
        return compute;
    }

    // 这句话是不是要求去掉这个字段。两种常见说法都认：“去掉部门”“不要部门列”，其余一律当不是
    private boolean afterRemoval(String text, String word) {
        if (TextUtils.isBlank(word)) return false;
        for (String verb : List.of("去掉", "删除", "不要", "别看", "撤掉", "取消")) {
            int at = text.indexOf(verb);
            while (at >= 0) {
                int hit = text.indexOf(word, at);
                if (hit >= 0 && hit - at <= verb.length() + 12) return true;
                at = text.indexOf(verb, at + verb.length());
            }
            // 反序：“部门列不要了”“入职日期去掉”——字段和动词之间最多隔两个字（列/这一列/这个）
            int fieldAt = text.indexOf(word);
            while (fieldAt >= 0) {
                int after = text.indexOf(verb, fieldAt + word.length());
                if (after >= 0 && after - fieldAt <= word.length() + 3) return true;
                fieldAt = text.indexOf(word, fieldAt + word.length());
            }
        }
        return false;
    }

    // 这句话里，“加上/增加/输出/保留”之后 12 个字内有没有提到这个字段
    private boolean afterAdd(String text, String word) {
        if (TextUtils.isBlank(word)) return false;
        for (String verb : List.of("加上", "增加", "添加", "输出", "保留", "要有", "补上", "带上")) {
            int at = text.indexOf(verb);
            while (at >= 0) {
                int hit = text.indexOf(word, at);
                if (hit >= 0 && hit - at <= verb.length() + 12) return true;
                at = text.indexOf(verb, at + verb.length());
            }
        }
        return false;
    }

    // 异常原因通常是“XX 为空”“XX 格式不对”，取前半段的字段名
    private String headOf(String reason) {
        if (TextUtils.isBlank(reason)) return "";
        String head = reason.split("[，,。；;（(]")[0].trim();
        return head.length() >= 2 ? head : "";
    }

    // 用户用一句话提修改要求时给模型看的提示：只做最小改动，没提到的部分别动。
    private String tweakPrompt(BridgeDataset dataset, BridgeDataset.Plan plan, String request) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在企业报表桥接智能体的“按一句话改方案”环节。用户已经看过一次交付结果，")
                .append("现在提了一句修改要求，请只在当前方案上做必要的修改，用户没提到的部分保持不变。\n\n");
        sb.append("用户的要求：").append(request).append("\n\n");
        sb.append("业务目标：").append(TextUtils.isBlank(plan.goal) ? "（由智能体按结构推断）" : plan.goal).append("\n");
        sb.append("数据表：\n");
        for (BridgeDataset.Table table : dataset.tables) {
            sb.append("- ").append(table.file).append("（类型 ").append(table.kind).append("，")
                    .append(table.rows).append(" 行）：")
                    .append(String.join("、", table.columns)).append("\n");
        }
        sb.append("已对齐字段：").append(String.join("、", plan.aligns.stream()
                .map(align -> align.column + "→" + align.label).distinct().toList())).append("\n");
        sb.append("当前方案：关联键=").append(plan.joinKey == null ? "无" : plan.joinLabel)
                .append("；主表=").append(primaryFileName(plan, dataset))
                .append("；计算列=").append(plan.computes.isEmpty() ? "无"
                        : String.join("，", plan.computes.stream().map(this::computeText).toList()))
                .append("；筛选=").append(plan.filters.isEmpty() ? "无" : String.join("；", plan.filters))
                .append("；异常检查=").append(plan.anomalyRules.isEmpty() ? "无"
                        : String.join("；", plan.anomalyRules))
                .append("；输出列=").append(String.join("、", plan.outputColumns)).append("\n");
        if (dataset.result != null) {
            sb.append("上一次执行结果：总计 ").append(dataset.result.totalRows).append(" 行，正常 ")
                    .append(dataset.result.okRows).append(" 行，异常 ").append(dataset.result.anomalyRows)
                    .append(" 行\n");
        }
        // 这句话可能引用了制度里的口径（"按公司规定入职当月不计绩效"），把相关条款一并给它
        sb.append(knowledgeBlock(request + " " + plan.goal + " " + dataset.name));
        sb.append("\n只返回一个 JSON 对象，不要解释，不要代码块：\n");
        sb.append("{\"join_key\": \"工号|姓名（要改才给）\", ");
        sb.append("\"compute\": [{\"name\":\"输出列名\",\"plus\":[\"标准字段\"],\"minus\":[\"标准字段\"]}], ");
        sb.append("\"filters\": [\"筛选说明\"], \"anomaly\": [{\"type\":\"join|blank|date|numeric|garbled|duplicate\",");
        sb.append("\"field\":\"标准字段\",\"reason\":\"异常原因\"}], ");
        sb.append("\"output_columns\": [\"标准字段或计算列名\"], \"primary_table\": \"数据文件名\", ");
        sb.append("\"summary\": \"一句话说明改了什么\", \"notes\": [\"提示\"]}\n");
        sb.append("约束：只能使用上面已对齐的标准字段名；user 没要求的在职/离职、日期区间不要自己加；")
                .append("异常检查里必须保留跨表关联检查（type=join）。\n");
        sb.append("如果这句话不需要改方案（例如只是提问），返回 {\"should_revise\": false, \"reason\": \"一句话说明\"}。\n");
        return sb.toString();
    }

    // 文件夹浏览：浏览器拿不到本机路径，所以由后端代读，供前端弹出目录选择框。
    public Map<String, Object> browse(String raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path root = paths.downloadsRoot();
        List<Map<String, Object>> entries = new ArrayList<>();
        Path current = null;
        if (!TextUtils.isBlank(raw)) {
            try {
                Path candidate = Paths.get(raw.trim());
                if (Files.isDirectory(candidate)) {
                    current = candidate.toAbsolutePath().normalize();
                } else {
                    out.put("error", "目录不存在：" + raw);
                }
            } catch (Exception e) {
                out.put("error", "路径不合法：" + raw);
            }
        }
        if (current != null) {
            try (Stream<Path> children = Files.list(current)) {
                children.filter(Files::isDirectory)
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .limit(300)
                        .forEach(child -> entries.add(dirEntry(child, false)));
            } catch (IOException e) {
                out.put("error", "读不了这个目录：" + e.getMessage());
            }
            out.put("path", current.toString());
            out.put("parent", current.getParent() == null ? "" : current.getParent().toString());
        } else {
            // 根页面：默认导出目录排第一，后面是磁盘根，方便直接跳到别的盘
            if (Files.isDirectory(root)) {
                entries.add(dirEntry(root, true));
            }
            for (File disk : File.listRoots()) {
                Path path = disk.toPath().toAbsolutePath().normalize();
                if (!path.equals(root.toAbsolutePath().normalize())) {
                    entries.add(dirEntry(path, true));
                }
            }
            out.put("path", "");
            out.put("parent", "");
        }
        out.put("entries", entries);
        out.put("default_dir", root.toString());
        out.put("current_existing", current != null);
        return out;
    }

    // 在指定目录下新建一个文件夹，供选择器里的「新建文件夹」用。
    public Map<String, Object> mkdir(String parent, String rawName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", "");
        out.put("error", "");

        String name = rawName == null ? "" : rawName.trim();
        if (TextUtils.isBlank(parent)) {
            out.put("error", "请先进入一个文件夹，再在里面新建");
            return out;
        }
        String bad = nameProblem(name);
        if (bad != null) {
            out.put("error", bad);
            return out;
        }

        Path base;
        try {
            base = Paths.get(parent.trim());
        } catch (Exception e) {
            out.put("error", "路径不合法：" + parent);
            return out;
        }
        if (!Files.isDirectory(base)) {
            out.put("error", "上级目录不存在：" + parent);
            return out;
        }
        try {
            Path created = base.resolve(name).toAbsolutePath().normalize();
            if (Files.exists(created)) {
                out.put("path", created.toString());
                out.put("error", "同名文件夹已存在，已直接打开它");
                return out;
            }
            Files.createDirectory(created);
            out.put("path", created.toString());
            return out;
        } catch (IOException e) {
            out.put("error", "新建文件夹失败：" + e.getMessage());
            return out;
        }
    }

    // 重命名选择器里的文件夹（右键菜单用）：只改名字，不动内容。
    public Map<String, Object> rename(String rawPath, String rawName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", "");
        out.put("error", "");

        String name = rawName == null ? "" : rawName.trim();
        String bad = nameProblem(name);
        if (bad != null) {
            out.put("error", bad);
            return out;
        }

        Path source;
        try {
            source = Paths.get(rawPath == null ? "" : rawPath.trim());
        } catch (Exception e) {
            out.put("error", "路径不合法：" + rawPath);
            return out;
        }
        if (!Files.isDirectory(source)) {
            out.put("error", "要重命名的文件夹不存在：" + rawPath);
            return out;
        }
        if (source.getParent() == null) {
            out.put("error", "磁盘根目录不能重命名");
            return out;
        }
        if (source.getFileName() != null && source.getFileName().toString().equals(name)) {
            // 名字没改，当作成功，前端不用提示
            out.put("path", source.toAbsolutePath().normalize().toString());
            return out;
        }

        try {
            Path target = source.resolveSibling(name).toAbsolutePath().normalize();
            if (Files.exists(target)) {
                out.put("error", "同名文件夹已存在，换个名字");
                return out;
            }
            Files.move(source, target);
            out.put("path", target.toString());
            return out;
        } catch (IOException e) {
            out.put("error", "重命名失败：" + e.getMessage());
            return out;
        }
    }

    // 文件夹名的公共校验：空、超长、含非法字符都返回一句中文原因，没问题返回 null
    private String nameProblem(String name) {
        if (TextUtils.isBlank(name)) return "文件夹名不能为空";
        if (name.length() > 60) return "文件夹名太长，控制在 60 个字符以内";
        // Windows 不允许这些字符，也允许斜杠的话会把目录层级写歪
        if (name.matches(".*[\\\\/:*?\"<>|].*")) return "文件夹名不能包含 \\ / : * ? \" < > |";
        return null;
    }

    private Map<String, Object> dirEntry(Path path, boolean root) {
        Map<String, Object> entry = new LinkedHashMap<>();
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        entry.put("name", name);
        entry.put("path", path.toString());
        entry.put("root", root);
        return entry;
    }

    // 兼容老入口：/run 也走同一套自主循环，参数取数据集上保存的业务目标
    public BridgeDataset run(String id) {
        BridgeDataset dataset = store.load(id);
        return auto(id, dataset == null ? null : dataset.instruction);
    }

    // 改可执行权限：手动审批 / 自动审批 / 完全访问。只改设置，不动已有方案和结果。
    public BridgeDataset access(String id, String access) {
        BridgeDataset dataset = store.load(id);
        if (dataset == null) throw new IllegalArgumentException("数据集不存在");
        String next = normalizeAccess(access);
        dataset.access = next;
        dataset.notes.add("可执行权限改为「" + accessLabel(next) + "」");
        store.save(dataset);
        return dataset;
    }

    // 手动审批时点「确认执行」：方案已经躺在那儿，不再重新规划，直接跑自主循环。
    public BridgeDataset approve(String id) {
        return guarded(id, () -> approveInternal(id));
    }

    private BridgeDataset approveInternal(String id) {
        BridgeDataset dataset = store.load(id);
        if (dataset == null) throw new IllegalArgumentException("数据集不存在");
        if (dataset.plan == null) throw new IllegalStateException("还没有方案，先让它把方案规划出来");
        if (!"awaiting".equals(dataset.status)) throw new IllegalStateException("这一单不在等待确认状态");
        BridgeDataset.Plan plan = dataset.plan;
        plan.confirmed = true;
        plan.auto = true;
        dataset.status = "running";
        dataset.notes.add("已确认执行");
        store.save(dataset);
        run.event(id, "execute", "", "已确认，开始执行", plan.summary, trace(
                "表数", dataset.tables.size(),
                "验收线", pct(plan.passRatio),
                "权限", accessLabel(dataset.access),
                "确认人", "本人"));
        autonomyLoop(dataset, plan, "");
        return dataset;
    }

    private static String normalizeAccess(String access) {
        String value = access == null ? "" : access.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case BridgeDataset.ACCESS_MANUAL, "手动", "手动审批" -> BridgeDataset.ACCESS_MANUAL;
            case BridgeDataset.ACCESS_FULL, "完全", "完全访问" -> BridgeDataset.ACCESS_FULL;
            default -> BridgeDataset.ACCESS_AUTO;
        };
    }

    private static String accessLabel(String access) {
        return switch (access == null ? "" : access) {
            case BridgeDataset.ACCESS_MANUAL -> "手动审批";
            case BridgeDataset.ACCESS_FULL -> "完全访问";
            default -> "自动审批";
        };
    }

    // 把 key/value 成对排一下，trace 里只留非空的值，省得界面上一排「null」
    private static Map<String, Object> trace(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i] == null || kv[i + 1] == null) continue;
            String key = String.valueOf(kv[i]);
            Object value = kv[i + 1];
            if (value instanceof String text && text.isBlank()) continue;
            out.put(key, value);
        }
        return out;
    }

    // 5. 对话

    // 会话式追问：交付之后可以问“为什么这么算”“异常为什么这么多”，也可以顺手说“3 月不要了”。
    public BridgeDataset chat(String id, String text) {
        // 提问不会改状态；但“改一句再跑”走的是同一条执行链，失败同样要落成 failed，
        // 所以这里也用 guarded。已交付的单在 markFailed 里会被跳过，不会把好好的结果判成败
        return guarded(id, () -> chatInternal(id, text));
    }

    private BridgeDataset chatInternal(String id, String text) {
        BridgeDataset dataset = store.load(id);
        if (dataset == null) throw new IllegalArgumentException("数据集不存在");
        String word = text == null ? "" : text.trim();
        if (word.isBlank()) throw new IllegalArgumentException("先说一句想问什么或想改什么");
        String at = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        dataset.chat.add(new BridgeDataset.Chat("user", word, at));

        // 还没方案：这句话就当成业务目标，走完整的自主流程（手动审批会停在确认）
        if (dataset.plan == null || dataset.result == null) {
            store.save(dataset);
            BridgeDataset after = autoInternal(id, word);
            BridgeDataset.Chat reply = new BridgeDataset.Chat("agent", after.status != null
                    && "awaiting".equals(after.status)
                    ? "按你说的重新规划了，方案已出，等你确认后执行"
                    : "按你说的重新跑完了：" + outcome(after), at);
            reply.action = after.result == null ? "none" : "rerun";
            fillOutcome(reply, after);
            after.chat.add(reply);
            store.save(after);
            return after;
        }

        String kind = "";
        String answer = "";
        String request = word;
        ChatModel model = chat.getIfAvailable();
        if (model != null) {
            try {
                JsonNode node = parseJson(askModel(dataset.id, chatPrompt(dataset, word)));
                if (node != null) {
                    kind = node.path("kind").asText("");
                    answer = node.path("answer").asText("");
                    String req = node.path("request").asText("");
                    if (!req.isBlank()) request = req;
                }
            } catch (Exception e) {
                dataset.notes.add("追问没走模型，按规则回答：" + e.getMessage());
            }
        }
        if (kind.isBlank()) kind = looksLikeQuestion(word) ? "question" : "change";

        if ("question".equals(kind)) {
            String reply = answer.isBlank() ? explain(dataset) : answer;
            BridgeDataset.Chat item = new BridgeDataset.Chat("agent", reply, at);
            item.action = "none";
            dataset.chat.add(item);
            store.save(dataset);
            run.event(id, "chat", "", "回答了追问：" + brief(word), reply);
            return dataset;
        }

        // 带改动的追问：改方案 + 重跑，回复里带上这一轮的数量
        BridgeDataset after = tweakInternal(id, request);
        BridgeDataset.Chat item = new BridgeDataset.Chat("agent", outcome(after), at);
        item.action = "rerun";
        fillOutcome(item, after);
        after.chat.add(item);
        store.save(after);
        return after;
    }

    private static void fillOutcome(BridgeDataset.Chat item, BridgeDataset dataset) {
        BridgeDataset.Result result = dataset.result;
        if (result == null) return;
        item.rounds = result.rounds;
        item.okRows = result.okRows;
        item.anomalyRows = result.anomalyRows;
        item.passed = result.passed;
    }

    private static String outcome(BridgeDataset dataset) {
        BridgeDataset.Result result = dataset.result;
        if (result == null) return "这一步还没产出结果";
        return "改了方案并重跑：" + result.rounds + " 轮，正常 " + result.okRows
                + " 行 / 异常 " + result.anomalyRows + " 行，"
                + (result.passed ? "已达到验收线" : "没达到验收线，已按最好的那一版交付");
    }

    private static boolean looksLikeQuestion(String text) {
        return text != null && text.matches(".*(吗|呢|为什么|为啥|如何|怎么|是否|是不是|多少|哪一|什么|请问|看看|能不能|有没有|解释).*");
    }

    private static String brief(String text) {
        String one = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return one.length() <= 24 ? one : one.substring(0, 24) + "…";
    }

    // 模型不可用时的回答：直接从方案里读，不加半点推测。
    private String explain(BridgeDataset dataset) {
        BridgeDataset.Plan plan = dataset.plan;
        StringBuilder out = new StringBuilder();
        if (TextUtils.isBlank(plan.goal)) {
            out.append("这一单的目标是按数据结构自己定的。");
        } else {
            out.append("目标是「").append(plan.goal).append("」。");
        }
        if (!plan.aligns.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            for (BridgeDataset.Align align : plan.aligns) {
                BridgeDataset.Table table = byId(dataset, align.tableId);
                pairs.add(align.label + "←" + (table == null ? "?" : table.file) + "." + align.column);
                if (pairs.size() == 6) break;
            }
            out.append("字段对齐：").append(String.join("、", pairs))
                    .append(plan.aligns.size() > 6 ? " 等 " + plan.aligns.size() + " 项。" : "。");
        }
        if (!TextUtils.isBlank(plan.joinKey)) {
            out.append("把各张表按「").append(TextUtils.isBlank(plan.joinLabel) ? plan.joinKey : plan.joinLabel)
                    .append("」拼在一起。");
        }
        if (!plan.computes.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (BridgeDataset.Compute compute : plan.computes) names.add(compute.name);
            out.append("算出来的列：").append(String.join("、", names)).append("。");
        }
        if (!plan.anomalyRules.isEmpty()) {
            out.append("异常判定：").append(String.join("；", plan.anomalyRules)).append("。");
        }
        out.append("验收线是正常行占比 ≥ ").append(pct(plan.passRatio)).append("，");
        if (plan.attempts.isEmpty()) {
            out.append("这次一轮就跑完，没有改方案。");
        } else {
            out.append("一共跑了 ").append(plan.attempts.size()).append(" 轮：");
            List<String> steps = new ArrayList<>();
            for (BridgeDataset.Attempt attempt : plan.attempts) {
                String tail = attempt.decision == null || attempt.decision.isBlank()
                        ? (attempt.verdict.isBlank() ? "" : "（" + attempt.verdict + "）")
                        : "→ " + attempt.decision;
                steps.add("第 " + attempt.round + " 轮 正常 " + attempt.okRows + " / 异常 "
                        + attempt.anomalyRows + (tail.isBlank() ? "" : " " + tail));
            }
            out.append(String.join("；", steps)).append("。");
        }
        BridgeDataset.Result result = dataset.result;
        if (result != null) {
            out.append("最终正常 ").append(result.okRows).append(" 行、异常 ").append(result.anomalyRows)
                    .append(" 行，").append(result.passed ? "已达标。" : "未达标，已按最好的那一版交付。");
        }
        return out.toString();
    }

    // 判一句追问是“只问”还是“要改”：只要 JSON，别让模型写长文
    private String chatPrompt(BridgeDataset dataset, String text) {
        BridgeDataset.Plan plan = dataset.plan;
        BridgeDataset.Result result = dataset.result;
        return """
                你在和一个刚看完跨系统表格桥接结果的业务同事对话。判断对方这句话是想了解情况，还是想让你改方案重跑。
                只输出 JSON：{"kind":"question|change","answer":"回答内容（kind=question 时写，40-120字，用结果里的真实数字）","request":"要改什么（kind=change 时写，一句话）"}
                不要输出 JSON 以外的任何东西。问情况时不要编造，方案里没有的就说不知道。
                如果下面的参考知识里有相关制度条款，回答可以引用它，但要说明出自哪份文件。

                业务目标：%s
                字段对齐：%s
                关联键：%s
                计算列：%s
                异常规则：%s
                验收线：正常行占比 ≥ %s
                当前结果：正常 %d 行 / 异常 %d 行，跑了 %d 轮，%s
                对方这句话：%s
                %s
                """.formatted(
                TextUtils.isBlank(plan.goal) ? "（未写，按结构推断）" : plan.goal,
                alignsBrief(plan),
                TextUtils.isBlank(plan.joinKey) ? "（单表，不需要关联）" : plan.joinKey,
                plan.computes.isEmpty() ? "（无）" : String.join("、", computeNames(plan)),
                plan.anomalyRules.isEmpty() ? "（无）" : String.join("；", plan.anomalyRules),
                pct(plan.passRatio),
                result == null ? 0 : result.okRows,
                result == null ? 0 : result.anomalyRows,
                result == null ? 0 : result.rounds,
                result != null && result.passed ? "已达标" : "未达标",
                text == null ? "" : text,
                knowledgeBlock(text == null ? "" : text));
    }

    // 给提示词拼一段「参考知识」：按这句话里的关键词去知识库检索，命中才加。
    private String knowledgeBlock(String query) {
        if (knowledge == null || knowledge.isEmpty()) return "";
        try {
            return knowledge.brief(query);
        } catch (Exception e) {
            log.warn("知识库检索失败（按没有参考知识继续）：{}", e.getMessage());
            return "";
        }
    }

    private static List<String> computeNames(BridgeDataset.Plan plan) {
        List<String> names = new ArrayList<>();
        for (BridgeDataset.Compute compute : plan.computes) names.add(compute.name);
        return names;
    }

    private static String alignsBrief(BridgeDataset.Plan plan) {
        List<String> pairs = new ArrayList<>();
        for (BridgeDataset.Align align : plan.aligns) {
            pairs.add(align.label + "←" + align.column);
            if (pairs.size() == 8) break;
        }
        return pairs.isEmpty() ? "（无）" : String.join("、", pairs);
    }

    // 前端轮询真实进度：返回 since 之后的新事件、当前阶段、是否已取消
    public Map<String, Object> events(String id, int since) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("数据集 id 不能为空");
        return run.snapshot(id, since);
    }

    // 取消这一单：只置标记，不强行中断线程（模型请求已经发出去了，中断只会留下半截状态）。
    public Map<String, Object> cancel(String id) {
        boolean wasRunning = run.snapshot(id, Integer.MAX_VALUE).get("running") instanceof Boolean bool && bool;
        run.cancel(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", wasRunning);
        out.put("message", wasRunning ? "已发出取消，当前这一步结束后停止" : "这一单已经跑完了");
        return out;
    }

    // 一轮结束时决定下一步：优先问模型（它能看出“关联键认错、少算一列”这类语义问题），模型没话说再退回规则判断
    private String decide(BridgeDataset dataset, BridgeDataset.Plan plan, BridgeDataset.Result result, int round) {
        ChatModel model = chat.getIfAvailable();
        if (model != null) {
            Snapshot before = snapshot(plan);
            try {
                JsonNode node = parseJson(askModel(dataset.id, reflectPrompt(dataset, plan, result, round)));
                if (node != null && node.has("should_revise")) {
                    if (!node.path("should_revise").asBoolean(false)) {
                        String reason = node.path("reason").asText("");
                        // 模型判断“这就是源数据的真实情况”，就不该动方案，否则等于为了百分比删检查
                        plan.notes.add("自主评估认定当前结果已是这份数据能做到的最好"
                                + (reason.isBlank() ? "" : "：" + reason));
                        return null;
                    }
                    String reason = node.path("reason").asText("");
                    Set<String> labels = new LinkedHashSet<>();
                    for (BridgeDataset.Align align : plan.aligns) labels.add(align.label);
                    applyModelPlan(plan, node, labels, dataset);
                    // 只改了说明没改执行参数，等于没改，直接交给规则兜底
                    if (!before.sameExec(plan)) {
                        return "按评估重排方案" + (reason.isBlank() ? "" : "（" + reason + "）");
                    }
                }
            } catch (Exception e) {
                plan.notes.add("自主评估跳过：" + e.getMessage());
            }
        }
        return decideByRules(plan, result);
    }

    // 模型用不上时的自主判断。只做两类最典型、且有把握的判断，一次只改一处，改完看结果有没有变好
    private String decideByRules(BridgeDataset.Plan plan, BridgeDataset.Result result) {
        String top = topReason(result);
        if (top == null) return null;

        boolean triedName = plan.attempts.stream()
                .anyMatch(a -> a.decision != null && a.decision.contains("姓名"));

        // 1) 过严的缺失/乱码检查：命中最多异常的那条，且字段不参与任何计算 → 判定为源数据问题
        String type = checkTypeOf(plan, top);
        if ("blank".equals(type) || "garbled".equals(type)) {
            Set<String> computed = new LinkedHashSet<>();
            for (BridgeDataset.Compute compute : plan.computes) {
                computed.addAll(compute.plus);
                computed.addAll(compute.minus);
            }
            BridgeDataset.AnomalyCheck tooStrict = plan.anomalyChecks.stream()
                    .filter(c -> isRemovable(c, top, computed))
                    .findFirst().orElse(null);
            if (tooStrict != null) {
                plan.anomalyChecks.remove(tooStrict);
                alignChecksToTables(plan);
                return "「" + tooStrict.label + "」大面积缺失，判断是源数据问题、不是这单的异常，"
                        + "撤掉这条隔离规则（标准表里该列仍留空，不过是不过滤）";
            }
        }

        // 2) 关联键可能认错：按工号一行都合不上（不是部分对不上），说明两边的工号根本不是同一个编号体系
        if (!triedName && "emp_no".equals(plan.joinKey) && top.contains("对不上")
                && result.okRows == 0 && nameJoinable(plan)) {
            plan.joinKey = "name";
            plan.joinLabel = labelOfField("name");
            return "按「工号」关联有大量对不上的记录，两张表都有姓名，改用「姓名」作为关联键再试一轮";
        }
        return null;
    }

    // 能不能撤这条检查：必须就是这个原因、不是关联/重复检查、字段也没参与计算
    private boolean isRemovable(BridgeDataset.AnomalyCheck check, String reason, Set<String> computed) {
        if (check.reason == null || !check.reason.equals(reason)) return false;
        if (!"blank".equals(check.type) && !"garbled".equals(check.type)) return false;
        return !computed.contains(check.label);
    }

    // 异常清单里命中最多的那条原因（一行可能同时命中多条，按单条原因计数）
    private String topReason(BridgeDataset.Result result) {
        if (result == null || result.anomalyPreview == null || result.anomalyPreview.isEmpty()) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : result.anomalyPreview) {
            String reasons = row.get("异常原因");
            if (reasons == null || reasons.isBlank()) continue;
            for (String reason : reasons.split("；")) {
                if (reason.isBlank()) continue;
                counts.merge(reason.trim(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String checkTypeOf(BridgeDataset.Plan plan, String reason) {
        for (BridgeDataset.AnomalyCheck check : plan.anomalyChecks) {
            if (check.reason != null && check.reason.equals(reason)) return check.type;
        }
        return "";
    }

    // 另外的表里是否也备好了姓名，能当关联键用
    private boolean nameJoinable(BridgeDataset.Plan plan) {
        if (plan.sources.size() < 2) return false;
        for (BridgeDataset.Source source : plan.sources) {
            boolean hasName = plan.aligns.stream()
                    .anyMatch(a -> source.tableId.equals(a.tableId) && "name".equals(a.field));
            if (!hasName) return false;
        }
        return true;
    }

    // 验收线：默认 90%，单表小数据可以放宽到 80%，避免为了百分比硬把源数据问题当异常
    private double passRatioOf(BridgeDataset dataset, BridgeDataset.Plan plan) {
        int rows = dataset.tables.stream().mapToInt(t -> t.rows).max().orElse(0);
        if (plan.sources.size() <= 1 && rows > 0 && rows < 50) return 0.8;
        return DEFAULT_PASS_RATIO;
    }

    // 验收标准：给人看的固定口径，和 passes() 用的是同一套判断
    private List<String> acceptanceOf(BridgeDataset dataset, BridgeDataset.Plan plan) {
        List<String> list = new ArrayList<>();
        list.add("正常行占比 ≥ " + pct(plan.passRatio) + "（命中异常的行进单独清单，不混入标准表）");
        if (plan.joinKey == null) {
            list.add("单表标准化，不做跨表关联");
        } else {
            list.add("按「" + plan.joinLabel + "」关联 " + plan.sources.size() + " 张表，关联不上的行必须进异常清单");
        }
        list.add("输出 " + plan.outputColumns.size() + " 列：" + String.join("、", plan.outputColumns));
        if (!plan.computes.isEmpty()) {
            list.add("计算列口径：" + String.join("、", plan.computes.stream().map(this::computeText).toList()));
        }
        list.add("原始文件只读，产出写到输出目录，方案改动可回滚");
        return list;
    }

    // 达标判断：只看“正常行占比”，这是唯一客观、能自动比对的验收项
    private boolean passes(BridgeDataset.Plan plan, BridgeDataset.Result result) {
        if (result == null || result.totalRows <= 0) return false;
        return result.okRows * 1d / result.totalRows >= plan.passRatio;
    }

    // 这一轮是不是比上一轮更有交付价值：正常行更多，或异常更少
    private boolean better(BridgeDataset.Result current, BridgeDataset.Result next) {
        if (next == null || next.totalRows <= 0) return false;
        return next.okRows > current.okRows || next.anomalyRows < current.anomalyRows;
    }

    private void recordAttempt(BridgeDataset.Plan plan, int round, BridgeDataset.Result result,
                               String decision, String verdict) {
        BridgeDataset.Attempt attempt = new BridgeDataset.Attempt();
        attempt.round = round;
        attempt.okRows = result == null ? 0 : result.okRows;
        attempt.anomalyRows = result == null ? 0 : result.anomalyRows;
        attempt.decision = decision == null ? "" : decision;
        attempt.verdict = verdict == null ? "" : verdict;
        attempt.ms = result == null ? 0 : result.elapsedMs;
        plan.attempts.add(attempt);
    }

    private BridgeDataset.Attempt lastAttempt(BridgeDataset.Plan plan) {
        return plan.attempts.isEmpty() ? new BridgeDataset.Attempt() : plan.attempts.get(plan.attempts.size() - 1);
    }

    private String pct(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    // 没给业务目标时，按数据集结构自己推断要做的事：先看同类型数据上次的目标，再看表名和列名里的线索
    private String inferGoal(BridgeDataset dataset) {
        String kinds = kindSignature(dataset);
        String remembered = memory.goalFor(kinds);
        if (!TextUtils.isBlank(remembered)) return remembered;

        boolean salary = hasKind(dataset, "erp_salary");
        boolean overtime = hasKind(dataset, "oa_overtime");
        boolean leave = hasKind(dataset, "oa_leave");
        boolean annual = hasKind(dataset, "hr_annual");
        String metrics = "";
        if (salary) metrics += "应发、实发、扣款";
        if (overtime) metrics += (metrics.isEmpty() ? "" : "，") + "加班小时";
        if (leave) metrics += (metrics.isEmpty() ? "" : "，") + "请假天数";
        if (annual) metrics += (metrics.isEmpty() ? "" : "，") + "年假余额";

        String head = dataset.tables.size() > 1
                ? "把 " + String.join("、", dataset.tables.stream().map(t -> t.kindLabel).distinct().toList())
                        + " 按工号合并成一人一行"
                : "整理 " + dataset.tables.get(0).kindLabel;
        String body = metrics.isEmpty()
                ? "，核对字段缺失、重复记录和格式异常"
                : "，统计" + metrics + "，核对缺失、对不上和重复的数据";
        return head + body + "，输出可直接导入的标准表";
    }

    // 按当前方案跑一遍，产出结果和三个文件；自主循环每一轮都走这里
    private BridgeDataset.Result execute(BridgeDataset dataset, BridgeDataset.Plan plan, long start) {
        Map<String, ExcelService.SheetData> sheets = new LinkedHashMap<>();
        Map<String, List<BridgeDataset.Align>> alignsByTable = new LinkedHashMap<>();
        for (BridgeDataset.Table table : dataset.tables) {
            Path path = store.filesDir(dataset.id).resolve(table.stored);
            if (!Files.exists(path)) continue;
            try {
                // 用户很可能上传完就在 Excel 里打开着这些文件，读不到绝大多数是“被占用”这种偶发错误
                sheets.put(table.id, retry("读取 " + table.file, IO_ATTEMPTS,
                        () -> excel.read(path, catalog, settings.mergedAliases(), true)));
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

        // 身份列（工号/姓名）里全角、空格、零宽字符这类脏值先替他收拾干净：
        // 不然“Ｅ００１ ”和“E001”会被当成两个人，关联对不上、还多出一堆假异常
        int repairs = repairIdentity(stdRows);
        if (repairs > 0) {
            noteOnce(plan, "自动修复 " + repairs + " 个工号/姓名里的全角、空格等不影响语义的脏值");
        }

        Map<String, String> labelToField = new LinkedHashMap<>();
        for (Std std : STD) labelToField.put(std.label(), std.key());

        LocalDate fromDate = TextUtils.parseDate(plan.dateFrom);
        LocalDate toDate = TextUtils.parseDate(plan.dateTo);

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

        // 日期区间和状态筛选要落在“原始记录”上：先筛再按工号聚合，否则区间外的记录会被算进去
        Set<String> filteredKeys = new LinkedHashSet<>();
        if (fromDate != null || plan.statusFilter != null) {
            for (Map.Entry<String, List<Map<String, String>>> entry : stdRows.entrySet()) {
                List<Map<String, String>> rows = entry.getValue();
                if (plan.joinKey != null && entry.getKey().equals(primary.id)) {
                    for (Map<String, String> row : rows) {
                        if (!filteredOut(row, plan, fromDate, toDate, labelToField)) continue;
                        String key = TextUtils.key(TextUtils.trim(row.get(plan.joinKey)));
                        if (!key.isEmpty()) filteredKeys.add(key);
                    }
                }
                rows.removeIf(row -> filteredOut(row, plan, fromDate, toDate, labelToField));
            }
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
                            entry.getKey() + "|" + TextUtils.key(key), k -> new LinkedHashMap<>());
                    mergeSum(target, row);
                }
            }
        }

        // 只有真正被用到的关联表才要求能对上：这单只统计加班时，缺一张假期表也照常跑
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

        // 规划阶段写下的异常检查到这里才真正跑；老数据集没有结构化检查时按默认规则补一份
        Set<String> planLabels = new LinkedHashSet<>();
        for (BridgeDataset.Align align : plan.aligns) planLabels.add(align.label);
        List<BridgeDataset.AnomalyCheck> checks = plan.anomalyChecks == null || plan.anomalyChecks.isEmpty()
                ? buildAnomalyChecks(plan, planLabels, dataset.instruction, dataset.instruction)
                : plan.anomalyChecks;
        boolean duplicateCheck = checks.stream().anyMatch(check -> "duplicate".equals(check.type));
        Set<String> duplicateKeys = duplicateCheck && plan.joinKey != null
                ? duplicateKeys(stdRows.get(primary.id), plan.joinKey) : Set.of();

        List<List<Object>> okCells = new ArrayList<>();
        List<List<Object>> anomalyCells = new ArrayList<>();
        List<Map<String, String>> preview = new ArrayList<>();
        List<Map<String, String>> anomalyPreview = new ArrayList<>();
        List<String> sourceLabels = dataset.tables.stream()
                .filter(t -> stdRows.containsKey(t.id)).map(t -> t.kindLabel).distinct().toList();

        // 主表也按关联键聚合成“一人一行”，同一个工号的多条记录累加，避免重复计数
        run.event(dataset.id, "execute", "join_tables", "按「" + (plan.joinLabel == null || plan.joinLabel.isBlank()
                        ? "主表自身" : plan.joinLabel) + "」关联 " + stdRows.size() + " 张表",
                "主表 " + primary.file + "，关联表按人聚合后再并进来");
        for (GroupRow ref : groupRows(stdRows.get(primary.id), plan.joinKey)) {
            Map<String, String> merged = new LinkedHashMap<>(ref.values());
            String key = ref.key();
            List<String> missingTables = new ArrayList<>();
            if (plan.joinKey != null && !TextUtils.isBlank(key)) {
                for (String tableId : stdRows.keySet()) {
                    if (tableId.equals(primary.id)) continue;
                    Map<String, String> fact = factAgg.get(tableId + "|" + TextUtils.key(key));
                    if (fact == null) {
                        if (requiredTables.containsKey(tableId)) missingTables.add(requiredTables.get(tableId));
                        continue;
                    }
                    for (Map.Entry<String, String> cell : fact.entrySet()) {
                        if (TextUtils.isBlank(merged.get(cell.getKey()))) merged.put(cell.getKey(), cell.getValue());
                    }
                }
            }

            String empNo = valueOfLabel(merged, "工号", labelToField);
            String name = valueOfLabel(merged, "姓名", labelToField);
            List<String> reasons = new ArrayList<>();
            for (BridgeDataset.AnomalyCheck check : checks) {
                String reason = reasonOf(check, merged, plan, missingTables, key, duplicateKeys, labelToField);
                if (reason != null && !reasons.contains(reason)) reasons.add(reason);
            }

            // 计算列可以引用前面已经算出来的列（“税后=实发工资-扣款”），
            // 所以每算完一列就放进 calcCtx，后面的计算式能直接取到它的值
            Map<String, String> computed = new LinkedHashMap<>();
            Map<String, String> calcCtx = new LinkedHashMap<>(merged);
            for (BridgeDataset.Compute compute : plan.computes) {
                Double value = computeValue(calcCtx, compute, labelToField);
                String text = value == null ? "" : TextUtils.numberText(value);
                computed.put(compute.name, text);
                calcCtx.put("col:" + compute.name, text);
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
                String value = TextUtils.key(TextUtils.trim(row.get(plan.joinKey)));
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
                    if (TextUtils.isBlank(rawKey) || primaryKeys.contains(TextUtils.key(rawKey))) continue;
                    // 这个人被日期/在职条件筛掉了，主表里没有他是正常的，别报成“对不上”
                    if (filteredKeys.contains(TextUtils.key(rawKey))) continue;
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

        run.event(dataset.id, "execute", "isolate_anomaly",
                "异常隔离：" + anomalyCells.size() + " 行进异常清单",
                "正常 " + okCells.size() + " 行留在标准表");
        // 清单有上限，命中上限时要说清楚，否则“总行数”看起来比源数据少一截，用户会以为丢数据
        if (anomalyCells.size() >= ANOMALY_LIMIT) {
            plan.notes.add("异常行超过 " + ANOMALY_LIMIT + " 行，异常清单只保留前 " + ANOMALY_LIMIT
                    + " 行；完整明细请回看源表，标准表里的正常行不受影响");
        }

        Path outDir = store.outDir(dataset.id);
        try {
            Files.createDirectories(outDir);
            // 导出目录常被用户开着（或者上一次的文件还没被 Excel 释放），写不进去先退避重试，
            // 不要因为一次“文件被占用”就把整单十几秒的成果判失败
            retry("导出 标准表.xlsx", IO_ATTEMPTS, () -> {
                excel.writeXlsx(outDir.resolve("标准表.xlsx"), plan.outputColumns, okCells);
                return null;
            });
            retry("导出 标准表.xls", IO_ATTEMPTS, () -> {
                excel.writeXls(outDir.resolve("标准表.xls"), plan.outputColumns, okCells);
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException("导出标准表失败：" + e.getMessage(), e);
        }
        List<String> anomalyColumns = List.of("工号", "姓名", "异常原因", "来源表", "原值摘要");
        try {
            retry("导出 异常清单.xlsx", IO_ATTEMPTS, () -> {
                excel.writeXlsx(outDir.resolve("异常清单.xlsx"), anomalyColumns, anomalyCells);
                return null;
            });
        } catch (Exception e) {
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
        result.repairs = repairs;
        run.event(dataset.id, "export", "standardize_export",
                "已导出 3 个文件：标准表 " + okCells.size() + " 行 / 异常 " + anomalyCells.size() + " 行",
                "标准表.xlsx、标准表.xls、异常清单.xlsx");
        return result;
    }

    // 身份列的常见脏值清理：全角转半角、去掉零宽字符、去掉所有空白。
    private int repairIdentity(Map<String, List<Map<String, String>>> stdRows) {
        int repaired = 0;
        for (List<Map<String, String>> rows : stdRows.values()) {
            for (Map<String, String> row : rows) {
                for (String field : List.of("emp_no", "name")) {
                    String value = row.get(field);
                    if (value == null || value.isEmpty()) continue;
                    String clean = cleanIdentity(value);
                    if (!clean.equals(value)) {
                        row.put(field, clean);
                        repaired++;
                    }
                }
            }
        }
        return repaired;
    }

    private String cleanIdentity(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\u3000' || ch <= ' ') continue;
            if (ch == '\u200B' || ch == '\uFEFF' || ch == '\u00A0') continue;
            if (ch >= '\uFF01' && ch <= '\uFF5E') ch = (char) (ch - 0xFEE0);
            sb.append(ch);
        }
        return sb.toString();
    }

    // 同一句提示只写一次，自主循环每一轮都会重跑，不去重会刷一屏
    private void noteOnce(BridgeDataset.Plan plan, String text) {
        if (!plan.notes.contains(text)) plan.notes.add(text);
    }

    // 方案里真正影响执行的那几个字段的快照：自主循环改完要看有没有实质变化，没变化就不算改进
    private class Snapshot {
        String joinKey;
        String joinLabel;
        String dateField;
        String dateFrom;
        String dateTo;
        String statusFilter;
        List<BridgeDataset.Compute> computes = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        List<String> outputColumns = new ArrayList<>();
        List<BridgeDataset.AnomalyCheck> checks = new ArrayList<>();
        List<String> roles = new ArrayList<>();

        boolean sameExec(BridgeDataset.Plan plan) {
            return execKey(this).equals(execKey(plan));
        }

        void restore(BridgeDataset.Plan plan) {
            plan.joinKey = joinKey;
            plan.joinLabel = joinLabel;
            plan.dateField = dateField;
            plan.dateFrom = dateFrom;
            plan.dateTo = dateTo;
            plan.statusFilter = statusFilter;
            plan.computes = new ArrayList<>(computes);
            plan.filters = new ArrayList<>(filters);
            plan.outputColumns = new ArrayList<>(outputColumns);
            plan.anomalyChecks = new ArrayList<>(checks);
            for (int i = 0; i < plan.sources.size() && i < roles.size(); i++) {
                plan.sources.get(i).role = roles.get(i);
            }
        }
    }

    private Snapshot snapshot(BridgeDataset.Plan plan) {
        Snapshot shot = new Snapshot();
        shot.joinKey = plan.joinKey;
        shot.joinLabel = plan.joinLabel;
        shot.dateField = plan.dateField;
        shot.dateFrom = plan.dateFrom;
        shot.dateTo = plan.dateTo;
        shot.statusFilter = plan.statusFilter;
        for (BridgeDataset.Compute compute : plan.computes) {
            BridgeDataset.Compute copy = new BridgeDataset.Compute();
            copy.name = compute.name;
            copy.plus = new ArrayList<>(compute.plus);
            copy.minus = new ArrayList<>(compute.minus);
            copy.origin = compute.origin;
            shot.computes.add(copy);
        }
        shot.filters = new ArrayList<>(plan.filters);
        shot.outputColumns = new ArrayList<>(plan.outputColumns);
        for (BridgeDataset.AnomalyCheck check : plan.anomalyChecks) {
            shot.checks.add(new BridgeDataset.AnomalyCheck(check.type, check.label, check.reason));
        }
        for (BridgeDataset.Source source : plan.sources) shot.roles.add(source.role);
        return shot;
    }

    private String execKey(Snapshot shot) {
        StringBuilder sb = new StringBuilder();
        sb.append(shot.joinKey).append('|').append(shot.dateField).append('|').append(shot.dateFrom)
                .append('|').append(shot.dateTo).append('|').append(shot.statusFilter).append('|')
                .append(String.join(";", shot.filters)).append('|')
                .append(String.join(",", shot.outputColumns)).append('|')
                .append(String.join(",", shot.roles)).append('|');
        for (BridgeDataset.Compute compute : shot.computes) {
            sb.append(computeText(compute)).append(';');
        }
        for (BridgeDataset.AnomalyCheck check : shot.checks) {
            sb.append(check.type).append(':').append(check.label).append(';');
        }
        return sb.toString();
    }

    private String execKey(BridgeDataset.Plan plan) {
        return execKey(snapshot(plan));
    }

    // 给模型看的“体检报告”：验收标准 + 方案要点 + 结果分布 + 已试过的改法 + 典型异常
    private String reflectPrompt(BridgeDataset dataset, BridgeDataset.Plan plan, BridgeDataset.Result result,
                                  int round) {
        double ratio = result.totalRows <= 0 ? 0 : result.anomalyRows * 1d / result.totalRows;
        StringBuilder sb = new StringBuilder();
        sb.append("你是企业报表桥接智能体的自主复核环节。这一轮已经跑完，但没达到验收标准，")
                .append("请你判断方案哪里理解偏了并给出修正；如果本来就该有这么多异常（源数据本身脏），")
                .append("就回答 should_revise=false，智能体会按当前结果交付。\n\n");
        sb.append("业务目标：").append(TextUtils.isBlank(plan.goal) ? "（由智能体按结构推断）" : plan.goal)
                .append("\n");
        sb.append("验收标准：\n");
        for (String item : plan.acceptance) sb.append("- ").append(item).append("\n");
        sb.append("这是第 ").append(round).append(" 轮（最多 ").append(MAX_AUTONOMOUS_ROUNDS).append(" 轮）\n");
        sb.append("数据表：\n");
        for (BridgeDataset.Table table : dataset.tables) {
            sb.append("- ").append(table.file).append("（类型 ").append(table.kind).append("，")
                    .append(table.rows).append(" 行）\n");
        }
        sb.append("已对齐字段：").append(joinLimited(
                plan.aligns.stream().map(a -> a.column + "→" + a.label).distinct().toList(), "、", 12)).append("\n");
        sb.append("当前方案：关联键=").append(plan.joinKey == null ? "无" : plan.joinLabel)
                .append("；主表=").append(primaryFileName(plan, dataset))
                .append("；计算列=").append(plan.computes.isEmpty() ? "无"
                        : String.join("，", plan.computes.stream().map(this::computeText).toList()))
                .append("；筛选=").append(plan.filters.isEmpty() ? "无" : String.join("；", plan.filters))
                .append("；输出列=").append(joinLimited(plan.outputColumns, "、", 12)).append("\n");
        sb.append("执行结果：总计 ").append(result.totalRows).append(" 行，正常 ").append(result.okRows)
                .append(" 行，异常 ").append(result.anomalyRows).append(" 行（占 ")
                .append(Math.round(ratio * 100)).append("%）\n");
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Map<String, String> row : result.anomalyPreview) {
            String text = row.getOrDefault("异常原因", "");
            if (text.isBlank()) continue;
            reasons.merge(text, 1, Integer::sum);
        }
        if (!reasons.isEmpty()) {
            sb.append("典型异常原因（按出现次数）：\n");
            reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                    .forEach(e -> sb.append("- ").append(e.getKey()).append("（").append(e.getValue()).append(" 条）\n"));
        }
        String tried = plan.attempts.stream()
                .map(a -> a.decision).filter(d -> d != null && !d.isBlank()).distinct()
                .reduce((a, b) -> a + "；" + b).orElse("");
        if (!tried.isBlank()) {
            sb.append("已经试过的改法（不要重复，重复的系统会自动回滚）：").append(tried).append("\n");
        }
        sb.append("\n只返回一个 JSON 对象，不要解释，不要代码块：\n");
        sb.append("{\"should_revise\": true|false, \"reason\": \"一句话说明判断\", ");
        sb.append("\"join_key\": \"工号|姓名（需要改才给）\", ");
        sb.append("\"compute\": [{\"name\":\"输出列名\",\"plus\":[\"标准字段\"],\"minus\":[\"标准字段\"]}], ");
        sb.append("\"filters\": [\"筛选说明\"], \"anomaly\": [{\"type\":\"join|blank|date|numeric|garbled|duplicate\",");
        sb.append("\"field\":\"标准字段\",\"reason\":\"异常原因\"}], ");
        sb.append("\"output_columns\": [\"标准字段或计算列名\"], \"summary\": \"方案概述\", \"notes\": [\"提示\"]}\n");
        sb.append("如果主从关系认反了（明细表被当成主表），用 \"primary_table\": \"数据文件名\" 指出应该当主表的那张。\n");
        sb.append("约束：只能使用上面已对齐的标准字段名；用户没提的在职/离职、日期区间不要自己加；")
                .append("异常检查里必须保留跨表关联检查（type=join）。\n");
        return sb.toString();
    }

    private String primaryFileName(BridgeDataset.Plan plan, BridgeDataset dataset) {
        return plan.sources.stream()
                .filter(s -> "主表".equals(s.role))
                .map(s -> byId(dataset, s.tableId))
                .filter(t -> t != null)
                .map(t -> t.file)
                .findFirst().orElse("未指定");
    }

    // 跑一条异常检查：命中就返回写进异常清单的说明，没命中返回 null
    private String reasonOf(BridgeDataset.AnomalyCheck check, Map<String, String> merged, BridgeDataset.Plan plan,
                            List<String> missingTables, String key, Set<String> duplicateKeys,
                            Map<String, String> labelToField) {
        String type = check.type == null ? "" : check.type;
        switch (type) {
            case "join": {
                if (plan.joinKey == null) return null;
                if (TextUtils.isBlank(key)) return "缺少" + plan.joinLabel;
                if (!missingTables.isEmpty()) {
                    return "在 " + String.join("、", missingTables) + " 里对不上这个" + plan.joinLabel;
                }
                return null;
            }
            case "blank": {
                return TextUtils.isBlank(valueOfLabel(merged, check.label, labelToField)) ? check.reason : null;
            }
            case "date": {
                String value = valueOfLabel(merged, check.label, labelToField);
                return !TextUtils.isBlank(value) && TextUtils.parseDate(value) == null ? check.reason : null;
            }
            case "numeric": {
                String value = valueOfLabel(merged, check.label, labelToField);
                return !TextUtils.isBlank(value) && TextUtils.parseNumber(value) == null ? check.reason : null;
            }
            case "garbled": {
                return TextUtils.garbled(valueOfLabel(merged, check.label, labelToField)) ? check.reason : null;
            }
            case "duplicate": {
                boolean duplicated = plan.joinKey != null && !TextUtils.isBlank(key)
                        && duplicateKeys.contains(TextUtils.key(key));
                return duplicated ? check.reason : null;
            }
            default:
                return null;
        }
    }

    // 主表里出现多次的工号：只在用户明确要求查重时才用来判异常
    private Set<String> duplicateKeys(List<Map<String, String>> rows, String joinKey) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String key = TextUtils.key(TextUtils.trim(row.get(joinKey)));
            if (!key.isEmpty()) count.merge(key, 1, Integer::sum);
        }
        Set<String> duplicated = new LinkedHashSet<>();
        count.forEach((key, times) -> {
            if (times > 1) duplicated.add(key);
        });
        return duplicated;
    }

    // 目标系统大多只认 yyyy-MM-dd，导出前把各种日期写法统一掉
    private String normalizeDate(String value) {
        if (TextUtils.isBlank(value)) return "";
        java.time.LocalDate date = TextUtils.parseDate(value);
        return date == null ? value : TextUtils.formatDate(date);
    }

    // 在职状态和日期区间都在原始记录上判断，聚合后再判断就失真了
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

    // 聚合后的主表一行：key 为关联键（没有键时为 null）
    private record GroupRow(String key, Map<String, String> values) {
    }

    // 主表按关联键合并。同一个工号可能有多条加班/请假记录，所以先按工号聚一下再算
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
            String norm = TextUtils.key(key);
            Map<String, String> group = groups.computeIfAbsent(norm, k -> new LinkedHashMap<>());
            mergeSum(group, row);
            keys.putIfAbsent(norm, key);
        }
        for (Map.Entry<String, Map<String, String>> entry : groups.entrySet()) {
            list.add(new GroupRow(keys.get(entry.getKey()), entry.getValue()));
        }
        return list;
    }

    // 数值字段跨行累加，其它字段只补空位
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

    // 5. 结果查询

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

    // 导出目录：用户设置过就存到他指定的文件夹，没设置就用前端工程根目录下的 downloads。
    private Path exportDir(BridgeDataset dataset) {
        String custom = settings.exportDir();
        Path root = TextUtils.isBlank(custom)
                ? paths.downloadsRoot()
                : Paths.get(custom).toAbsolutePath().normalize();
        return root.resolve(safeName(dataset.name) + "-" + exportStamp(dataset));
    }

    // 导出子文件夹的时间后缀：取数据集上传时刻的 yyyyMMddHHmmss（14 位数字）。
    private String exportStamp(BridgeDataset dataset) {
        return stamp(dataset.createdAt);
    }

    // 时间后缀的换算本身：带秒取 14 位，只有分钟的旧数据保持 12 位，没有时间就用当前时刻。
    static String stamp(String createdAt) {
        String digits = createdAt == null ? "" : createdAt.replaceAll("[^0-9]", "");
        if (digits.length() >= 14) {
            return digits.substring(0, 14);
        }
        if (!digits.isEmpty()) {
            return digits.length() >= 12 ? digits.substring(0, 12)
                    : digits + "0".repeat(12 - digits.length());
        }
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    // 默认导出目录（绝对值），前端设置里做占位提示用
    public String defaultExportRoot() {
        return paths.downloadsRoot().toString();
    }

    // 当前生效的导出根目录：设置过自定义路径就是它，否则是默认目录
    public String exportRoot() {
        String custom = settings.exportDir();
        return TextUtils.isBlank(custom) ? paths.downloadsRoot().toString() : custom;
    }

    // 交付后改导出位置：把这一单已经导出的文件从旧目录搬到新目录，不重跑。
    public Map<String, Object> relocate(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", false);
        out.put("dir", "");
        out.put("error", "");
        if (TextUtils.isBlank(id)) {
            return out;
        }
        BridgeDataset dataset;
        try {
            dataset = store.load(id);
        } catch (RuntimeException e) {
            // 记录可能已经被清理掉了，这时只改设置项，不报错
            return out;
        }
        if (dataset == null || dataset.result == null || !"done".equals(dataset.status)) {
            return out;
        }
        Path target = exportDir(dataset);
        out.put("dir", target.toString());
        Path source = TextUtils.isBlank(dataset.result.downloadDir)
                ? null : Paths.get(dataset.result.downloadDir).toAbsolutePath().normalize();
        if (source != null && source.equals(target)) {
            // 位置没变，不用搬
            return out;
        }
        try {
            Files.createDirectories(target);
            for (String name : dataset.result.artifacts) {
                Path old = source == null ? null : source.resolve(name);
                if (old != null && Files.isRegularFile(old)) {
                    moveFile(old, target.resolve(name));
                    continue;
                }
                // 旧目录已经不在了（被手动清理过）：从 data 目录里的原件补一份
                Path origin = store.outDir(dataset.id).resolve(name);
                if (Files.isRegularFile(origin)) {
                    Files.copy(origin, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (source != null) {
                deleteIfEmpty(source);
            }
            dataset.result.downloadDir = target.toString();
            store.save(dataset);
            out.put("moved", true);
        } catch (IOException e) {
            out.put("error", "移动导出文件失败：" + e.getMessage());
        }
        return out;
    }

    // 跨磁盘直接 move 会失败，退回「复制 + 删原件」；导出文件不大，够用
    private void moveFile(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(from);
        }
    }

    // 旧目录搬空后顺手删掉，避免留下一堆空文件夹
    private void deleteIfEmpty(Path dir) {
        try {
            Files.delete(dir);
        } catch (IOException ignored) {
            // 里面还有别的文件就留着
        }
    }

    // 数据集名里可能带路径分隔符和冒号，换成能当文件夹名的安全字符
    private String safeName(String raw) {
        String value = raw == null || raw.isBlank() ? "dataset" : raw.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return value.length() > 40 ? value.substring(0, 40) : value;
    }

    // 清理最近记录前的保底动作：先把这一单已导出的表复制到导出目录。
    public int preserveArtifacts(String id) {
        BridgeDataset dataset;
        try {
            dataset = store.load(id);
        } catch (RuntimeException e) {
            // 记录已经不在了，没东西可保
            return 0;
        }
        if (dataset == null || dataset.result == null || dataset.result.artifacts.isEmpty()) {
            return 0;
        }
        mirrorArtifacts(dataset, dataset.result);
        return dataset.result.artifacts.size();
    }

    // 把导出文件同步一份到前端目录的 downloads。
    private void mirrorArtifacts(BridgeDataset dataset, BridgeDataset.Result result) {
        if (dataset == null || result == null || result.artifacts.isEmpty()) return;
        Path dir = exportDir(dataset);
        try {
            Files.createDirectories(dir);
            for (String name : result.artifacts) {
                Path from = store.outDir(dataset.id).resolve(name);
                if (Files.isRegularFile(from)) {
                    Files.copy(from, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            result.downloadDir = dir.toString();
        } catch (IOException e) {
            log.warn("导出文件同步到 downloads 失败：{}", e.getMessage());
        }
    }

    // 打开导出文件所在文件夹（Windows 会直接定位到该文件），给前端“前往文件夹”用。
    public Map<String, Object> reveal(String id, String artifact) {
        BridgeDataset dataset = store.load(id);
        if (dataset == null) {
            throw new IllegalArgumentException("数据集不存在");
        }
        Path dir = dataset.result != null && !TextUtils.isBlank(dataset.result.downloadDir)
                ? Paths.get(dataset.result.downloadDir).normalize() : exportDir(dataset);
        // 老数据集还没同步过：这次点开时补一份，用户点“前往文件夹”总能拿到文件
        if (dataset.result != null && Files.notExists(dir)) {
            mirrorArtifacts(dataset, dataset.result);
            if (!TextUtils.isBlank(dataset.result.downloadDir)) {
                dir = Paths.get(dataset.result.downloadDir).normalize();
            }
        }
        Path file = TextUtils.isBlank(artifact) ? null : dir.resolve(artifact).normalize();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir.toString());
        out.put("file", file == null ? "" : file.toString());
        boolean opened = false;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = file != null && Files.exists(file)
                        ? new ProcessBuilder("explorer.exe", "/select," + file)
                        : new ProcessBuilder("explorer.exe", dir.toString());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", dir.toString());
            } else {
                pb = new ProcessBuilder("xdg-open", dir.toString());
            }
            pb.start();
            opened = true;
            // Windows 只把窗口放到后面闪任务栏：后台进程拉起的窗口拿不到前台焦点。
            // 这里补一刀，按目录名把资源管理器窗口提到最前，失败也不影响主流程。
            if (os.contains("win")) {
                bringExplorerToFront(dir);
            }
        } catch (Exception e) {
            log.warn("打开导出文件夹失败：{}", e.getMessage());
        }
        out.put("opened", opened);
        return out;
    }

    // 把已经打开的资源管理器窗口提到前台。
    private void bringExplorerToFront(Path dir) {
        try {
            Path name = dir.getFileName();
            if (name == null || name.toString().isBlank()) {
                return;
            }
            ProcessBuilder focus = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-Command", "Invoke-Expression $env:HB_FOCUS");
            focus.environment().put("HBFOLDER", name.toString());
            focus.environment().put("HB_FOCUS", FOCUS_SCRIPT);
            focus.redirectErrorStream(true);
            focus.start();
        } catch (Exception e) {
            log.debug("资源管理器置前失败：{}", e.getMessage());
        }
    }

    // 置前脚本：按标题找到目标窗口，恢复显示后挂到当前前台线程上再抢焦点。
    private static final String FOCUS_SCRIPT = """
            $ErrorActionPreference='SilentlyContinue'
            Add-Type -TypeDefinition @'
            using System;
            using System.Text;
            using System.Runtime.InteropServices;
            public class WinFocus {
            public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
            [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr p);
            [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
            [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
            [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
            [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr p);
            [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
            [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
            [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
            [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
            [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
            public static IntPtr Find(string needle) {
            IntPtr found = IntPtr.Zero;
            EnumWindows(delegate(IntPtr h, IntPtr l) {
            if (!IsWindowVisible(h)) return true;
            StringBuilder sb = new StringBuilder(512);
            GetWindowText(h, sb, 512);
            string t = sb.ToString();
            if (t.Length > 0 && t.Contains(needle)) { found = h; return false; }
            return true;
            }, IntPtr.Zero);
            return found;
            }
            public static bool Activate(IntPtr h) {
            if (h == IntPtr.Zero) return false;
            ShowWindow(h, 9);
            IntPtr fg = GetForegroundWindow();
            uint fgThread = GetWindowThreadProcessId(fg, IntPtr.Zero);
            uint myThread = GetCurrentThreadId();
            AttachThreadInput(myThread, fgThread, true);
            bool ok = SetForegroundWindow(h);
            BringWindowToTop(h);
            AttachThreadInput(myThread, fgThread, false);
            return ok;
            }
            }
            '@
            for ($i = 0; $i -lt 15; $i++) {
            $h = [WinFocus]::Find($env:HBFOLDER)
            if ($h -ne [IntPtr]::Zero) {
            [WinFocus]::Activate($h) | Out-Null
            Start-Sleep -Milliseconds 150
            if ([WinFocus]::GetForegroundWindow() -eq $h) { break }
            }
            Start-Sleep -Milliseconds 120
            }
            """;

    public BridgeDataset get(String id) {
        return store.load(id);
    }

    public List<BridgeDataset> recent() {
        return store.recent();
    }
}
