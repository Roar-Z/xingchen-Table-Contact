package com.xingchen.huibiao.service.agent;

import com.xingchen.huibiao.dto.ApiRequests.AnalyzeRequest;
import com.xingchen.huibiao.dto.ApiRequests.FileRef;
import com.xingchen.huibiao.model.AgentPlan;
import com.xingchen.huibiao.model.AgentStep;
import com.xingchen.huibiao.model.FileMeta;
import com.xingchen.huibiao.model.IntentResult;
import com.xingchen.huibiao.service.ExcelService;
import com.xingchen.huibiao.service.FileService;
import com.xingchen.huibiao.service.IntentService;
import com.xingchen.huibiao.service.TemplateResolver;
import com.xingchen.huibiao.service.governance.TokenMeter;
import com.xingchen.huibiao.util.TextUtils;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 配置阶段的智能体编排。
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    // 模型最多连续调用多少次工具，防止在缺列问题上无限重试。
    private static final int MAX_TOOL_TURNS = 14;

    private final FileService files;
    private final IntentService intentService;
    private final TemplateResolver resolver;
    private final ExperienceStore experience;
    private final ObjectProvider<ChatModel> chatModel;
    private final TokenMeter tokenMeter;

    public AgentService(FileService files, IntentService intentService,
                        TemplateResolver resolver, ExperienceStore experience,
                        ObjectProvider<ChatModel> chatModel, TokenMeter tokenMeter) {
        this.files = files;
        this.intentService = intentService;
        this.resolver = resolver;
        this.experience = experience;
        this.chatModel = chatModel;
        this.tokenMeter = tokenMeter;
    }

    public AgentPlan plan(AnalyzeRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("先选至少一个源表");
        }
        long started = System.currentTimeMillis();
        tokenMeter.begin();
        AgentPlan plan = null;
        try {
            ChatModel model = chatModel.getIfAvailable();
            if (model != null) {
                try {
                    plan = new Session(request).runWithModel(model);
                } catch (RuntimeException e) {
                    // 模型不可达、输出不合法或没走完工具链时，用同一套工具按固定顺序补一遍
                    log.warn("模型工具循环失败，改用规则编排重跑：{}", e.getMessage());
                    plan = new Session(request).runInOrder();
                    plan.fallback = true;
                    plan.summary = "模型编排未完成，本次由规则工具链兜底：" + brief(e.getMessage());
                }
            } else {
                plan = new Session(request).runInOrder();
            }
            return plan;
        } finally {
            // 模型调用都在本线程上，结束后统一落 token 和总耗时，异常时也保留消耗
            TokenMeter.Metered used = tokenMeter.end();
            if (plan != null) {
                plan.durationMs = System.currentTimeMillis() - started;
                plan.tokensIn = used.inputTokens();
                plan.tokensOut = used.outputTokens();
                plan.modelCalls = used.modelCalls();
            }
        }
    }

    // ==================== 模型可调用的工具集 ====================

    // 一次规划会话：持有本次的文件、意图、经验、缺失列等状态，
    public final class Session {

        private final AnalyzeRequest request;
        private AgentPlan plan = new AgentPlan();
        private List<Loaded> loaded = new ArrayList<>();
        private IntentResult intent;
        private ExperienceStore.Item matched;
        private List<String> missing = new ArrayList<>();
        private boolean inspected;
        private boolean detected;
        private boolean recalled;
        private boolean validated;
        private boolean handedOff;

        private String inspectSummary = "";
        private String detectSummary = "";
        private String recallSummary = "";
        private String handoffSummary = "";
        // 预置给模型的“已经算好的事实”，避免它再花几轮把同样的工具重跑一遍
        private String preparedNote = "";

        Session(AnalyzeRequest request) {
            this.request = request;
        }

        // ---------- 工具 1：读结构 ----------

        @Tool(name = "inspect_sources",
                value = "读取源表结构。返回每张表的文件名、表头所在行、数据行数、列名和少量样例值。"
                        + "系统已预读并写在任务描述里，一般不需要再调用；只有在怀疑描述与实际不符时才复查。")
        public String inspectSources() {
            if (inspected) {
                return inspectSummary;
            }
            long mark = System.currentTimeMillis();
            load();
            AgentStep step = step("inspect", "读取源表结构",
                    "共 " + loaded.size() + " 张表，逐张核对表头与数据量", "inspect_sources", "ok");
            for (Loaded item : loaded) {
                step.item(item.meta.name + "：表头第 " + item.meta.headerRow + " 行，"
                        + item.rows + " 行数据，" + item.columns.size() + " 列");
            }
            plan.steps.add(done(step, mark));
            inspected = true;
            inspectSummary = describeLoaded();
            return inspectSummary;
        }

        // ---------- 工具 2：识别场景 ----------

        @Tool(name = "detect_intent",
                value = "识别业务场景（假期余量结算 / 加班记录桥接）、业务口径、目标模板和筛选条件。"
                        + "必须在 inspect_sources 之后调用；没有识别出场景时要如实说明失败原因。")
        public String detectIntent() {
            if (detected) {
                return detectSummary;
            }
            if (!inspected) {
                return "还没有读过源表结构，请先调用 inspect_sources。";
            }
            long mark = System.currentTimeMillis();
            IntentResult result;
            try {
                result = intentService.analyze(request.brief(), request.files(), request.templateFileId());
            } catch (RuntimeException e) {
                AgentStep fail = step("plan", "识别业务场景失败", e.getMessage(), "detect_intent", "fail");
                plan.steps.add(done(fail, mark));
                throw e;
            }
            intent = result;
            plan.intent = result;
            plan.mode = result.parser != null && result.parser.contains("langchain4j") ? "llm" : "rules";
            AgentStep detect = step("plan", "识别业务场景与业务口径",
                    result.sceneName + "（把握 " + Math.round(result.confidence * 100) + "%）",
                    "detect_intent", "ok");
            detect.item("解析器：" + ("llm".equals(plan.mode) ? "语义模型 + 规则兜底" : "纯规则"));
            detect.item("目标模板：" + (result.targetTemplate == null ? "未识别" : result.targetTemplate.name()));
            if (!result.filters.isEmpty()) {
                detect.item("筛选条件：" + String.join("；", result.filters));
            }
            plan.steps.add(done(detect, mark));
            detected = true;
            detectSummary = describeIntent();
            return detectSummary;
        }

        // ---------- 工具 3：查记忆 ----------

        @Tool(name = "recall_experience",
                value = "检索历史经验：过去同类表用过的模板和映射。命中后可以调用 apply_experience 补缺，"
                        + "但不能覆盖本次已经推导出的有效映射。")
        public String recallExperience() {
            if (recalled) {
                return recallSummary;
            }
            if (!detected) {
                return "还没有识别场景，请先调用 detect_intent。";
            }
            long mark = System.currentTimeMillis();
            List<String> sourceColumns = new ArrayList<>();
            loaded.forEach(item -> sourceColumns.addAll(item.columns));
            matched = experience.bestMatch(intent.scene,
                    intent.targetTemplate == null ? "" : intent.targetTemplate.name(), sourceColumns);
            AgentStep recall = step("recall", "检索历史经验",
                    matched == null ? "没有同类历史记录，本次从零推导"
                            : "命中一条经验（用过 " + matched.wins + " 次，模板 " + matched.templateName + "）",
                    "recall_experience", matched == null ? "skip" : "ok");
            if (matched != null) {
                plan.experienceId = matched.id;
                plan.experienceLabel = "命中历史经验" + (TextUtils.isBlank(matched.note) ? "" : "：" + matched.note);
                recall.item("历史映射 " + matched.mapping.size() + " 条");
            }
            plan.steps.add(done(recall, mark));
            recalled = true;
            recallSummary = matched == null
                    ? "没有可复用的历史经验。"
                    : "命中历史经验 " + matched.id + "，共 " + matched.mapping.size() + " 条映射，可以调用 apply_experience 补缺。";
            return recallSummary;
        }

        // ---------- 工具 4：复用经验 ----------

        @Tool(name = "apply_experience",
                value = "把命中的历史映射里当前还缺的目标列补上，只补空缺，不覆盖已有映射。")
        public String applyExperience() {
            if (!detected) {
                return "还没有识别场景，请先调用 detect_intent。";
            }
            long mark = System.currentTimeMillis();
            int filled = fillFromExperience(intent, loaded, matched);
            AgentStep apply;
            if (matched == null) {
                apply = step("tool", "复用历史映射补齐缺口",
                        "没有可复用的经验，跳过这一步，直接用本次推导结果", "apply_experience", "skip");
            } else if (filled > 0) {
                apply = step("tool", "复用历史映射补齐缺口",
                        "从经验里补了 " + filled + " 条映射", "apply_experience", "ok");
            } else {
                apply = step("tool", "复用历史映射补齐缺口",
                        "命中的经验与当前映射一致，无需补缺", "apply_experience", "skip");
            }
            plan.steps.add(done(apply, mark));
            return apply.detail;
        }

        // ---------- 工具 5：校验 ----------

        @Tool(name = "validate_mapping",
                value = "校验目标模板的必填列是否都有可用来源。返回缺失列；每次校验都会留下一条反思记录，"
                        + "自动补全后可以再次调用来确认。")
        public String validateMapping() {
            if (!detected) {
                return "还没有识别场景，请先调用 detect_intent。";
            }
            long mark = System.currentTimeMillis();
            missing = missingRequired(intent, loaded);
            AgentStep validate = step("reflect", "校验映射完整性",
                    missing.isEmpty() ? "必填列都有来源，映射结构完整"
                            : "还有 " + missing.size() + " 个必填列没有来源",
                    "validate_mapping", missing.isEmpty() ? "ok" : "warn");
            missing.forEach(name -> validate.item("缺来源：" + name));
            plan.steps.add(done(validate, mark));
            validated = true;
            return missing.isEmpty()
                    ? "校验通过：所有必填列都有来源。"
                    : "校验未通过，缺失必填列：" + String.join("、", missing) + "。可以调用 auto_fix 尝试自动补全。";
        }

        // ---------- 工具 6：自动补全 ----------

        @Tool(name = "auto_fix",
                value = "对缺失的必填列尝试自动补全：日期类用“日期 + 时间”拼接，时长类用“天 × 8”换算。"
                        + "只有源表确实有对应列时才动手，补不了就留给人工。补完应再次调用 validate_mapping 确认。")
        public String autoFix() {
            if (!detected) {
                return "还没有识别场景，请先调用 detect_intent。";
            }
            if (!validated) {
                return "还没有校验过映射，请先调用 validate_mapping。";
            }
            long mark = System.currentTimeMillis();
            List<String> fixes = new ArrayList<>();
            boolean needFix = !missing.isEmpty();
            int fixed = autoFixMissing(intent, loaded, missing, fixes);
            AgentStep fix;
            if (!needFix) {
                fix = step("fix", "自动补全缺失列",
                        "校验已通过，没有需要自动修正的列", "auto_fix", "skip");
            } else if (fixed > 0) {
                fix = step("fix", "自动补全缺失列",
                        "用拼接/换算规则补了 " + fixed + " 列，已写进映射表，确认时可改", "auto_fix", "ok");
                fixes.forEach(fix::item);
                plan.fixes.addAll(fixes);
                missing = missingRequired(intent, loaded);
                validated = true;
            } else {
                fix = step("fix", "自动补全缺失列",
                        "缺失列没有可自动拼接的源列，保持空缺交人工处理", "auto_fix", "skip");
            }
            plan.steps.add(done(fix, mark));
            return fixed > 0
                    ? "已自动补全 " + fixed + " 列。建议再调用 validate_mapping 复核，剩余缺口：" +
                    (missing.isEmpty() ? "无" : String.join("、", missing))
                    : "没有可以自动补全的列，这些缺口需要人工指定。";
        }

        // ---------- 工具 7：交人 ----------

        @Tool(name = "handoff_human",
                value = "生成待人工确认的方案：汇总映射数量、自动生成数量和仍需人工处理的列。"
                        + "任务最后必须调用一次，任何自动结论都不能直接执行。")
        public String handoffHuman() {
            if (!detected) {
                return "还没有识别场景，请先调用 detect_intent。";
            }
            if (handedOff) {
                return handoffSummary;
            }
            if (!validated) {
                missing = missingRequired(intent, loaded);
                validated = true;
            }
            long mark = System.currentTimeMillis();
            int mapped = (int) intent.mapping.stream().filter(row -> valid(row, loaded)).count();
            AgentStep handoff = step("handoff", "生成待确认方案",
                    "共 " + mapped + " 条映射，其中自动生成 " + automatic(intent) + " 条，确认后进入业务规则",
                    "handoff_human", "ok");
            if (!missing.isEmpty()) {
                handoff.item("需要人工补：" + String.join("、", missing));
                handoff.status = "warn";
                plan.handoff.add("还有 " + missing.size() + " 个必填列没有来源，补完才能执行");
            }
            plan.steps.add(done(handoff, mark));
            handedOff = true;
            handoffSummary = "方案已生成，共 " + mapped + " 条映射"
                    + (missing.isEmpty() ? "，等待人工确认。" : "，还有 " + missing.size() + " 个必填列需要人工补。");
            return handoffSummary;
        }

        // ==================== 两条运行方式 ====================

        // 真智能体：由模型复核预置结果、决定是否补救，并生成待人工确认的方案。
        AgentPlan runWithModel(ChatModel model) {
            // 读表 / 识别场景 / 查记忆 / 校验 / 自动补全都是确定性操作，先在本地按依赖顺序跑完，
            if (!inspected) {
                inspectSources();
            }
            detectIntent();
            recallExperience();
            applyExperience();
            validateMapping();
            autoFix();
            handoffHuman();
            preparedNote = describePrepared();

            Planner planner = AiServices.builder(Planner.class)
                    .chatModel(model)
                    .tools(this)
                    .maxSequentialToolsInvocations(MAX_TOOL_TURNS)
                    .build();
            String answer = planner.run(describeTask());
            if (intent == null) {
                throw new IllegalArgumentException("模型没有完成场景识别，无法生成方案");
            }
            if (!handedOff) {
                handoffHuman();
            }
            plan.mode = "llm";
            plan.summary = clip(answer);
            return finish();
        }

        // 离线兜底：同一套工具，顺序写死，保证没有模型也能出方案。
        AgentPlan runInOrder() {
            inspectSources();
            detectIntent();
            recallExperience();
            applyExperience();
            validateMapping();
            autoFix();
            handoffHuman();
            return finish();
        }

        private AgentPlan finish() {
            plan.intent = intent;
            for (int i = 0; i < plan.steps.size(); i++) {
                plan.steps.get(i).index = i + 1;
            }
            log.info("智能体完成配置编排：场景 {}，映射 {} 条，自动修正 {} 处，工具 {} 次，模式 {}",
                    intent == null ? "-" : intent.scene,
                    intent == null ? 0 : intent.mapping.size(),
                    plan.fixes.size(), plan.steps.size(), plan.mode);
            return plan;
        }

        private void load() {
            for (FileRef ref : request.files()) {
                FileMeta meta = files.require(ref.fileId());
                ExcelService.SheetData sheet = files.readSheet(meta);
                List<String> columns = sheet == null ? new ArrayList<>() : sheet.headers();
                int rows = sheet == null ? 0 : sheet.rows().size();
                loaded.add(new Loaded(meta, columns, rows, sheet));
            }
        }

        // 给模型的任务上下文：描述、文件清单和模板，具体表头由工具返回。
        private String describeTask() {
            StringBuilder sb = new StringBuilder();
            sb.append("任务描述：").append(TextUtils.isBlank(request.brief()) ? "（用户没有额外描述）" : request.brief()).append("\n");
            sb.append("源表（fileId / 文件名）：\n");
            for (FileRef ref : request.files()) {
                try {
                    FileMeta meta = files.require(ref.fileId());
                    sb.append("- ").append(ref.fileId()).append(" / ").append(meta.name).append("\n");
                } catch (RuntimeException e) {
                    sb.append("- ").append(ref.fileId()).append(" / 读取失败：").append(e.getMessage()).append("\n");
                }
            }
            sb.append("目标模板：")
                    .append(TextUtils.isBlank(request.templateFileId()) ? "未指定，使用内置 HR 导入模板" : request.templateFileId())
                    .append("\n");
            sb.append("\n源表结构（系统已预读，结果如下，不要再调用 inspect_sources）：\n")
                    .append(inspectSummary);
            if (!preparedNote.isEmpty()) {
                sb.append(preparedNote);
                sb.append("\n方案已经生成并等待人工确认，你不需要再调用任何工具。\n")
                        .append("请只用一句话（不超过 30 字）说清：识别到的场景、映射了几列、还剩几列要人工补。\n")
                        .append("只有当你发现上面的事实与任务描述明显矛盾时，才调用对应工具修正一次。\n");
            } else {
                sb.append("\n请从 detect_intent 开始调用工具。");
            }
            return sb.toString();
        }

        // 把已经跑完的工具结果压缩成一段事实，让模型一次就能读完判断。
        private String describePrepared() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n已完成的工具结果（事实，直接采信）：\n");
            sb.append(describeIntent());
            if (matched != null) {
                sb.append("历史经验：命中 ").append(matched.id).append("，其中 ")
                        .append(matched.mapping.size()).append(" 条映射可复用。\n");
            } else {
                sb.append("历史经验：没有同类历史记录。\n");
            }
            sb.append("必填校验：")
                    .append(missing.isEmpty() ? "所有必填列都有来源" : "缺 " + String.join("、", missing))
                    .append("\n");
            if (!plan.fixes.isEmpty()) {
                sb.append("自动补全：").append(String.join("；", plan.fixes)).append("\n");
            }
            sb.append("当前映射条数：").append(intent.mapping.size()).append("\n");
            return sb.toString();
        }

        private String describeLoaded() {
            StringBuilder sb = new StringBuilder();
            for (Loaded item : loaded) {
                sb.append(item.meta.name).append("：表头第 ").append(item.meta.headerRow)
                        .append(" 行，").append(item.rows).append(" 行数据，列名 ")
                        .append(String.join("、", item.columns)).append("。");
                Map<String, String> sample = item.sampleValues();
                if (!sample.isEmpty()) {
                    sb.append(" 样例：");
                    sample.forEach((column, value) -> sb.append(column).append('=').append(value).append("；"));
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        private String describeIntent() {
            if (intent == null) {
                return "没有识别出场景。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("场景：").append(intent.sceneName).append("（" ).append(intent.scene).append("）\n");
            sb.append("目标模板：")
                    .append(intent.targetTemplate == null ? "无" : intent.targetTemplate.name()).append("\n");
            if (intent.targetTemplate != null) {
                sb.append("模板列：").append(String.join("、", intent.targetTemplate.columns())).append("\n");
            }
            if (!intent.filters.isEmpty()) {
                sb.append("筛选条件：").append(String.join("；", intent.filters)).append("\n");
            }
            sb.append("当前规则映射：").append(intent.mapping.size()).append(" 条。");
            return sb.toString();
        }
    }

    // 模型只负责决定调用哪些工具，数值和文件操作都在 Java 里完成。
    public interface Planner {

        @SystemMessage("""
                你是企业表格桥接智能体，负责把源表和目标模板变成可执行、可核对的字段映射方案。
                你必须通过调用工具来完成任务，不能凭空编造列名、文件名或数据，也不要自己计算数值。

                工作原则：
                1. 任务描述里已经写好“已完成的工具结果”（读表、场景、历史经验、必填校验、自动补全、方案交接），
                   这些是事实，直接采信，不要重复调用任何工具去重算。
                2. 只有当你发现事实与任务描述明显矛盾时，才调用对应工具修正一次，不要反复试。
                3. 必须调用 handoff_human 生成待人工确认的方案，任何自动结论都不能直接执行（已生成时它会直接返回缓存）。
                4. 工具返回失败或缺口时，如实说明；补不了的列留给人工，不要编造。
                5. 最后只用一两句话（合计不超过 40 字）总结方案要点和仍需人工确认的地方，然后立即结束；
                   不要罗列步骤，不要预告“接下来将调用某工具”。
                6. 用户等待时间是成本：默认路径就是一句话总结，能一步做完就不要拆成两轮。
                """)
        String run(@UserMessage String task);
    }

    // ==================== 下面是纯 Java 工具实现 ====================

    private int fillFromExperience(IntentResult intent, List<Loaded> loaded, ExperienceStore.Item item) {
        if (item == null || item.mapping.isEmpty() || intent.targetTemplate == null) {
            return 0;
        }
        Set<String> targets = new LinkedHashSet<>(intent.targetTemplate.columns());
        int filled = 0;
        for (ExperienceStore.Entry entry : item.mapping) {
            if (!targets.contains(entry.target)) {
                continue;
            }
            IntentResult.MappingSuggestion current = findByTarget(intent, entry.target);
            if (current != null && valid(current, loaded)) {
                continue;
            }
            Loaded source = findFile(loaded, entry.fileName, entry.column, entry.expr);
            if (source == null) {
                continue;
            }
            if (!TextUtils.isBlank(entry.expr)) {
                if (!com.xingchen.huibiao.service.ColumnExpressions.isValid(entry.expr,
                        new LinkedHashSet<>(source.columns))) {
                    continue;
                }
            } else if (!source.columns.contains(entry.column)) {
                continue;
            }
            if (current == null) {
                current = new IntentResult.MappingSuggestion(entry.target,
                        requiredOf(intent, entry.target), source.meta.fileId, entry.column, 0.9, "experience");
                current.expression = TextUtils.isBlank(entry.expr) ? null : entry.expr;
                intent.mapping.add(current);
            } else {
                current.fileId = source.meta.fileId;
                current.column = TextUtils.isBlank(entry.expr) ? entry.column : null;
                current.expression = TextUtils.isBlank(entry.expr) ? null : entry.expr;
                current.confidence = 0.9;
                current.origin = "experience";
            }
            filled++;
        }
        return filled;
    }

    // 自动补缺：日期类目标用“日期 + 时间”拼，时长类目标用“天 × 8”换算。
    private int autoFixMissing(IntentResult intent, List<Loaded> loaded, List<String> missing, List<String> fixes) {
        if (missing.isEmpty()) {
            return 0;
        }
        int fixed = 0;
        for (String target : new ArrayList<>(missing)) {
            TemplateResolver.Resolved resolved = resolver.resolve(target, intent.scene);
            if (resolved == null) {
                continue;
            }
            String key = resolved.key();
            Loaded source = mainSource(loaded);
            if (source == null) {
                continue;
            }
            String expr = null;
            if ("start".equals(key) || "end".equals(key)) {
                String dateCol = findDateColumn(source, key);
                String timeCol = findTimeColumn(source, dateCol);
                if (dateCol != null && timeCol != null) {
                    // `+` 表示日期 + 时刻合并成标准日期时间
                    expr = "{" + dateCol + "} + {" + timeCol + "}";
                } else if (dateCol != null) {
                    expr = "{" + dateCol + "}";
                }
            } else if ("date".equals(key)) {
                // 纯日期列（加班日期这类）只取日期，不拼时刻
                String dateCol = findDateColumn(source);
                if (dateCol != null) {
                    expr = "{" + dateCol + "}";
                }
            } else if ("hours".equals(key) || "overtime_hours".equals(key)) {
                String days = findColumn(source, "天", "天数", "请假天数");
                if (days != null) {
                    expr = "{" + days + "}*8";
                }
            }
            if (expr == null) {
                continue;
            }
            IntentResult.MappingSuggestion current = findByTarget(intent, target);
            if (current == null) {
                current = new IntentResult.MappingSuggestion(target, requiredOf(intent, target),
                        source.meta.fileId, null, 0.85, "agent");
                current.expression = expr;
                intent.mapping.add(current);
            } else {
                current.fileId = source.meta.fileId;
                current.column = null;
                current.expression = expr;
                current.confidence = 0.85;
                current.origin = "agent";
            }
            fixes.add("「" + target + "」没有单列来源，按 " + expr + " 自动生成");
            fixed++;
        }
        return fixed;
    }

    private List<String> missingRequired(IntentResult intent, List<Loaded> loaded) {
        List<String> missing = new ArrayList<>();
        if (intent.targetTemplate == null) {
            return missing;
        }
        for (String target : intent.targetTemplate.columns()) {
            TemplateResolver.Resolved resolved = resolver.resolve(target, intent.scene);
            if (resolved == null || !resolved.required()) {
                continue;
            }
            if ("no_clock".equals(resolved.key())) {
                continue;
            }
            IntentResult.MappingSuggestion row = findByTarget(intent, target);
            if (row == null || !valid(row, loaded)) {
                missing.add(target);
            }
        }
        return missing;
    }

    private boolean valid(IntentResult.MappingSuggestion row, List<Loaded> loaded) {
        if (row == null) {
            return false;
        }
        if (!TextUtils.isBlank(row.expression)) {
            Loaded source = findFile(loaded, null, null, row.expression, row.fileId);
            return source != null && com.xingchen.huibiao.service.ColumnExpressions
                    .isValid(row.expression, new LinkedHashSet<>(source.columns));
        }
        if (TextUtils.isBlank(row.column) || TextUtils.isBlank(row.fileId)) {
            return false;
        }
        return loaded.stream()
                .filter(item -> row.fileId.equals(item.meta.fileId))
                .anyMatch(item -> item.columns.contains(row.column));
    }

    private int automatic(IntentResult intent) {
        return (int) intent.mapping.stream()
                .filter(row -> "ai".equals(row.origin) || "agent".equals(row.origin) || "experience".equals(row.origin))
                .count();
    }

    private IntentResult.MappingSuggestion findByTarget(IntentResult intent, String target) {
        return intent.mapping.stream()
                .filter(row -> TextUtils.norm(row.target).equals(TextUtils.norm(target)))
                .findFirst()
                .orElse(null);
    }

    private boolean requiredOf(IntentResult intent, String target) {
        IntentResult.MappingSuggestion row = findByTarget(intent, target);
        return row != null && row.required;
    }

    private Loaded mainSource(List<Loaded> loaded) {
        return loaded.stream()
                .filter(item -> "leave_request".equals(item.meta.role) || "overtime".equals(item.meta.role))
                .findFirst()
                .orElse(loaded.isEmpty() ? null : loaded.get(0));
    }

    private Loaded findFile(List<Loaded> loaded, String fileName, String column, String expr) {
        return findFile(loaded, fileName, column, expr, null);
    }

    private Loaded findFile(List<Loaded> loaded, String fileName, String column, String expr, String fileId) {
        if (!TextUtils.isBlank(fileId)) {
            for (Loaded item : loaded) {
                if (fileId.equals(item.meta.fileId)) {
                    return item;
                }
            }
        }
        if (!TextUtils.isBlank(fileName)) {
            for (Loaded item : loaded) {
                if (fileName.equals(item.meta.name)) {
                    return item;
                }
            }
        }
        if (!TextUtils.isBlank(column)) {
            for (Loaded item : loaded) {
                if (item.columns.contains(column)) {
                    return item;
                }
            }
        }
        if (!TextUtils.isBlank(expr)) {
            Set<String> used = com.xingchen.huibiao.service.ColumnExpressions.placeholders(expr);
            for (Loaded item : loaded) {
                if (item.columns.containsAll(used)) {
                    return item;
                }
            }
        }
        return null;
    }

    // 日期列。key=start/end 时优先配对应那一侧（起始日期 / 结束日期），
    private String findDateColumn(Loaded source, String key) {
        String fallback = null;
        for (String column : source.columns) {
            if (TextUtils.norm(column).contains("日期") && sampleParsesDate(source, column)) {
                if (fallback == null) {
                    fallback = column;
                }
                if (key != null && key.equals(sideOf(column))) {
                    return column;
                }
            }
        }
        return fallback;
    }

    private String findDateColumn(Loaded source) {
        return findDateColumn(source, null);
    }

    // 时间列在 Excel 里是“时分秒”，解析不出完整日期；用它和日期列区分开。
    private String findTimeColumn(Loaded source, String dateColumn) {
        String want = dateColumn == null ? null : sideOf(dateColumn);
        String fallback = null;
        for (String column : source.columns) {
            String name = TextUtils.norm(column);
            if ((name.contains("时间") || name.contains("时刻")) && !name.contains("日期")
                    && !sampleParsesDate(source, column)) {
                if (fallback == null) {
                    fallback = column;
                }
                if (want != null && want.equals(sideOf(column))) {
                    return column;
                }
            }
        }
        return fallback;
    }

    private static String sideOf(String column) {
        String name = TextUtils.norm(column);
        if (name.contains("始")) {
            return "start";
        }
        if (name.contains("止") || name.contains("结束")) {
            return "end";
        }
        return null;
    }

    private String findColumn(Loaded source, String... names) {
        for (String name : names) {
            for (String column : source.columns) {
                if (TextUtils.norm(column).equals(TextUtils.norm(name))) {
                    return column;
                }
            }
        }
        return null;
    }

    private boolean sampleParsesDate(Loaded source, String column) {
        if (source.sheet == null) {
            return false;
        }
        int limit = Math.min(source.sheet.rows().size(), 20);
        for (int i = 0; i < limit; i++) {
            Map<String, String> values = source.sheet.rows().get(i).values();
            if (TextUtils.parseDate(values.get(column)) != null) {
                return true;
            }
        }
        return false;
    }

    private AgentStep step(String phase, String title, String detail, String tool, String status) {
        return new AgentStep(phase, title, detail, tool, status);
    }

    private AgentStep done(AgentStep step, long mark) {
        step.elapsedMs = System.currentTimeMillis() - mark;
        return step;
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() <= 360 ? value : value.substring(0, 360) + "…";
    }

    private String brief(String text) {
        if (text == null || text.isBlank()) {
            return "未返回可执行方案";
        }
        return clip(text);
    }

    private record Loaded(FileMeta meta, List<String> columns, int rows, ExcelService.SheetData sheet) {

        Map<String, String> sampleValues() {
            Map<String, String> sample = new java.util.LinkedHashMap<>();
            if (sheet == null) {
                return sample;
            }
            int limit = Math.min(sheet.rows().size(), 40);
            for (int i = 0; i < limit && sample.size() < 20; i++) {
                Map<String, String> values = sheet.rows().get(i).values();
                for (String column : columns) {
                    if (sample.size() >= 20) {
                        break;
                    }
                    String value = values.get(column);
                    if (!sample.containsKey(column) && !TextUtils.isBlank(value)) {
                        String trimmed = TextUtils.trim(value);
                        sample.put(column, trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed);
                    }
                }
            }
            return sample;
        }
    }
}
