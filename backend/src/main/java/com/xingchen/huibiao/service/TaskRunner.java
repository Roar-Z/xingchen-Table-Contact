package com.xingchen.huibiao.service;

import com.xingchen.huibiao.dto.ApiRequests.CreateTaskRequest;
import com.xingchen.huibiao.dto.ApiRequests.MappingEntry;
import com.xingchen.huibiao.dto.ApiRequests.TaskConfig;
import com.xingchen.huibiao.model.AgentStep;
import com.xingchen.huibiao.model.FileMeta;
import com.xingchen.huibiao.model.IntentResult;
import com.xingchen.huibiao.model.ProcessResult;
import com.xingchen.huibiao.model.TaskRecord;
import com.xingchen.huibiao.service.agent.ExperienceStore;
import com.xingchen.huibiao.service.sheet.SheetService;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 异步执行器。新建任务接口只负责排队，真正的读表、计算、写文件都在这里跑，所以接口能立刻返回
@Component
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final TaskStore store;
    private final FileService files;
    private final IntentService intent;
    private final SceneRouter router;
    private final ExportService export;
    private final SettingsService settings;
    private final ExperienceStore experience;
    private final ResultEditService edits;
    private final SheetService sheetService;

    public TaskRunner(TaskStore store, FileService files, IntentService intent,
                      SceneRouter router, ExportService export, SettingsService settings,
                      ExperienceStore experience, ResultEditService edits, SheetService sheetService) {
        this.store = store;
        this.files = files;
        this.intent = intent;
        this.router = router;
        this.export = export;
        this.settings = settings;
        this.experience = experience;
        this.edits = edits;
        this.sheetService = sheetService;
    }

    @Async("taskExecutor")
    public void run(String taskId, CreateTaskRequest request) {
        long started = System.currentTimeMillis();
        TaskRecord task = store.find(taskId).orElse(null);
        if (task == null) {
            log.warn("任务 {} 已被删除，跳过执行", taskId);
            return;
        }
        // 自然语言改结果的记录不能跟着 snapshot 一起被覆盖，先记下来，算完再重放
        Object revisions = task.config == null ? null : task.config.get("edits");
        try {
            task.status = "running";
            task.progress = 10;
            addLog(task, "info", "开始处理，共 " + request.files().size() + " 个源文件");
            store.put(task);

            listSources(task, request);
            task.progress = 22;
            store.put(task);

            String templateFileId = request.config() == null ? null : request.config().templateFileId();
            IntentResult plan = intent.analyze(request.brief(), request.files(), templateFileId);
            plan = router.align(plan, request.config());
            task.scene = plan.scene;
            task.sceneName = plan.sceneName;
            task.target = plan.targetTemplate.name();
            task.outputColumns = new ArrayList<>(plan.targetTemplate.columns());
            task.config = snapshot(request, plan);
            if (revisions != null) {
                task.config.put("edits", revisions);
            }

            addLog(task, "info", "识别场景：" + plan.sceneName + "（" + Math.round(plan.confidence * 100) + "% 把握）");
            if (!plan.filters.isEmpty()) {
                addLog(task, "info", "筛选条件：" + String.join("；", plan.filters));
            }
            task.progress = 40;
            store.put(task);

            ProcessResult result = router.process(request.files(), plan, request.config());
            // 一行都没出来时，智能体按自己刚才推导的映射重跑一次，再决定是否判失败
            result = selfRepair(task, request, plan, result);
            // 用户用自然语言提过的修改，按记录顺序重放到这次结果上
            applyRevisions(task, result);
            task.totalRows = result.totalRows;
            task.outputRows = result.outputRows;
            task.anomalyRows = result.anomalyRows;
            task.preview = result.preview;
            task.anomalies = result.anomalies;
            task.warnings = result.warnings;
            task.stats = result.stats;
            task.config.put("rules", result.rules);
            task.progress = 66;

            addLog(task, "info", "输出 " + result.outputRows + " 行，异常隔离 " + result.anomalyRows + " 行");
            statMessage(task, result, plan.scene);
            for (int i = 0; i < Math.min(3, result.warnings.size()); i++) {
                addLog(task, "warn", result.warnings.get(i));
            }
            store.put(task);

            if (result.outputRows == 0) {
                task.status = "failed";
                task.error = result.totalRows == 0 ? "源表没有读到有效数据" : "按当前条件筛选后没有可输出的数据";
                addLog(task, "err", task.error);
                finish(task, started);
                return;
            }

            task.progress = 78;
            store.put(task);
            task.artifacts = export.write(task, result, plan.scene);
            for (TaskRecord.Artifact artifact : task.artifacts) {
                addLog(task, "info", "生成 " + artifact.name + "（" + artifact.size + "）");
            }

            double ratio = result.totalRows == 0 ? 0 : result.anomalyRows * 100d / result.totalRows;
            double threshold = settings.numberRule("anomaly_threshold", 20);
            if (result.anomalyRows > 0) {
                task.status = "warning";
                if (ratio > threshold) {
                    String tip = String.format("异常占比 %.1f%%，超过设置的 %.0f%%，建议先看异常日志再导入 HR。", ratio, threshold);
                    task.warnings.add(tip);
                    addLog(task, "warn", tip);
                }
            } else {
                task.status = "done";
            }
            task.progress = 100;
            addLog(task, "ok", "任务完成，产出 " + task.artifacts.size() + " 个文件");
            // 结果表同步收进「我的仓库」，处理日志一并带过去
            archive(task);
            // 只要产出了结果，这套映射就值得记下来；异常行由人工复核，不影响下次复用配置
            if (result.outputRows > 0) {
                remember(task, plan, request);
            }
            finish(task, started);
        } catch (Exception e) {
            log.error("任务 {} 执行失败", taskId, e);
            task.status = "failed";
            task.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            task.progress = 100;
            addLog(task, "err", "处理失败：" + task.error + "。源表没有被修改，可以检查后重跑。");
            finish(task, started);
        }
    }

    // 把用户用自然语言提过的修改重放到本次结果上。
    private void applyRevisions(TaskRecord task, ProcessResult result) {
        int changed;
        try {
            changed = edits.apply(task, result);
        } catch (RuntimeException e) {
            addLog(task, "warn", "你的修改没有应用成功：" + e.getMessage() + "，本次按原始结果交付");
            return;
        }
        if (changed <= 0) {
            return;
        }
        String summary = edits.describe(task);
        AgentStep step = new AgentStep("reflect", "按你的话改动结果",
                "共改动 " + changed + " 行，导出文件同步更新", "revise_result", "ok");
        step.item(summary);
        step.elapsedMs = 0L;
        addStep(task, step);
        addLog(task, "ok", "已按你的话修改结果：" + summary);
    }

    // 智能体执行阶段的自愈：结果是 0 行时，说明映射和源表对不上（多半是人工指错了列，或模型给的列名近似但不是同一个），这里按推荐重建一遍映射重跑
    private ProcessResult selfRepair(TaskRecord task, CreateTaskRequest request,
                                     IntentResult plan, ProcessResult broken) {
        if (broken.outputRows > 0 || broken.totalRows == 0) {
            return broken;
        }
        if (request.config() == null || request.config().mapping() == null
                || request.config().mapping().isEmpty()) {
            return broken;
        }
        long mark = System.currentTimeMillis();
        AgentStep fix = new AgentStep("fix", "结果全为异常，按智能体推荐重建映射重跑",
                "原映射产出 0 行，异常 " + broken.anomalyRows + " 行", "self_repair", "warn");
        if (broken.anomalies != null && !broken.anomalies.isEmpty()) {
            fix.item("首个异常：第 " + broken.anomalies.get(0).row() + " 行 "
                    + broken.anomalies.get(0).reason());
        }
        List<MappingEntry> rebuilt = mappingFromPlan(plan);
        if (rebuilt.isEmpty() || sameMapping(rebuilt, request.config().mapping())) {
            fix.status = "skip";
            fix.detail = "推荐映射和当前配置一致，没有可替换的方案，仍判失败";
            fix.elapsedMs = System.currentTimeMillis() - mark;
            addStep(task, fix);
            addLog(task, "warn", "智能体检查后没有可替换的映射，保持失败状态");
            return broken;
        }
        TaskConfig config = new TaskConfig(plan.scene, rebuilt,
                request.config().templateFileId(), request.config().isolateAnomaly(),
                request.config().strictFormat());
        ProcessResult retry = router.process(request.files(), plan, config);
        fix.elapsedMs = System.currentTimeMillis() - mark;
        if (retry.outputRows > 0) {
            fix.status = "ok";
            fix.detail = "改用推荐映射后输出 " + retry.outputRows + " 行，已自动恢复";
            fix.item("替换映射 " + rebuilt.size() + " 条");
            addStep(task, fix);
            // 自愈后的映射写回快照，重新执行和经验库都按修好的版本走
            task.config.put("mapping", snapshotOf(rebuilt));
            task.config.put("agent_repaired", true);
            addLog(task, "ok", "智能体自愈成功：按推荐映射重跑，输出 " + retry.outputRows + " 行");
            return retry;
        }
        fix.detail = "按推荐映射重跑后仍然 0 行";
        addStep(task, fix);
        addLog(task, "warn", "智能体自愈未成功，按推荐映射重跑仍然没有可输出数据");
        return retry;
    }

    // 把解析结果里的建议映射转成可执行配置，只保留真正能对上源列的条目。
    private List<MappingEntry> mappingFromPlan(IntentResult plan) {
        List<MappingEntry> list = new ArrayList<>();
        if (plan.mapping == null) {
            return list;
        }
        for (IntentResult.MappingSuggestion row : plan.mapping) {
            if (TextUtils.isBlank(row.expression)) {
                if (TextUtils.isBlank(row.fileId) || TextUtils.isBlank(row.column)) {
                    continue;
                }
                list.add(new MappingEntry(row.target, row.fileId, row.column, null));
            } else {
                list.add(new MappingEntry(row.target, row.fileId, null, row.expression));
            }
        }
        return list;
    }

    private List<Map<String, Object>> snapshotOf(List<MappingEntry> entries) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MappingEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("target", entry.target());
            item.put("file_id", entry.fileId());
            item.put("column", entry.column());
            item.put("expr", entry.expr());
            list.add(item);
        }
        return list;
    }

    private boolean sameMapping(List<MappingEntry> left, List<MappingEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            MappingEntry a = left.get(i);
            MappingEntry b = right.get(i);
            if (!String.valueOf(a.target()).equals(String.valueOf(b.target()))
                    || !String.valueOf(a.column()).equals(String.valueOf(b.column()))
                    || !String.valueOf(a.expr()).equals(String.valueOf(b.expr()))) {
                return false;
            }
        }
        return true;
    }

    // 跑成功就把这套配置记成经验，下次遇到同类表先复用，不用再从头配。
    private void remember(TaskRecord task, IntentResult plan, CreateTaskRequest request) {
        try {
            List<ExperienceStore.Entry> entries = new ArrayList<>();
            for (MappingEntry entry : mappingEntries(task, request, plan)) {
                // 固定值列（比如免打卡）没有源列，不需要记进经验
                if (TextUtils.isBlank(entry.column()) && TextUtils.isBlank(entry.expr())) {
                    continue;
                }
                String fileName = files.find(entry.fileId()).map(meta -> meta.name).orElse("");
                entries.add(new ExperienceStore.Entry(entry.target(), fileName,
                        entry.column() == null ? "" : entry.column(),
                        entry.expr() == null ? "" : entry.expr()));
            }
            List<String> columns = new ArrayList<>();
            for (var ref : request.files()) {
                files.find(ref.fileId()).ifPresent(meta -> columns.addAll(meta.columns));
            }
            String templateName = plan.targetTemplate == null ? task.target : plan.targetTemplate.name();
            if (experience.learn(plan.scene, templateName, columns, entries, task.name) != null) {
                addStep(task, new AgentStep("reflect", "记住这次配置",
                        "已存入经验库，下次同类表会优先复用这套映射", "learn_experience", "ok"));
            }
        } catch (Exception e) {
            log.warn("记录智能体经验失败：{}", e.getMessage());
        }
    }

    // 记忆要以“最终生效的映射”为准：执行期自愈会把修好的映射写回 task.config，所以先读这份快照，没有再退回规划时的映射
    private List<MappingEntry> mappingEntries(TaskRecord task, CreateTaskRequest request, IntentResult plan) {
        Object snapshot = task.config == null ? null : task.config.get("mapping");
        if (snapshot instanceof List<?> list && !list.isEmpty()) {
            List<MappingEntry> entries = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                String target = asText(row.get("target"));
                if (TextUtils.isBlank(target)) {
                    continue;
                }
                entries.add(new MappingEntry(target, asText(row.get("file_id")),
                        asText(row.get("column")), asText(row.get("expr"))));
            }
            if (!entries.isEmpty()) {
                return entries;
            }
        }
        if (request.config() != null && request.config().mapping() != null
                && !request.config().mapping().isEmpty()) {
            return request.config().mapping();
        }
        return mappingFromPlan(plan);
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void listSources(TaskRecord task, CreateTaskRequest request) {
        for (var ref : request.files()) {
            FileMeta meta = files.find(ref.fileId()).orElse(null);
            if (meta == null) {
                continue;
            }
            int rows = meta.sheets.isEmpty() ? 0 : meta.sheets.get(0).rows;
            addLog(task, "info", meta.name + "：表头第 " + meta.headerRow + " 行，有效数据 " + rows + " 行，"
                    + meta.columns.size() + " 列");
        }
    }

    private void statMessage(TaskRecord task, ProcessResult result, String scene) {
        if ("leave".equals(scene)) {
            addLog(task, "info", "年假抵扣 " + TextUtils.numberText(number(result.stats.get("annual_hours")))
                    + " 小时，事假 " + TextUtils.numberText(number(result.stats.get("personal_hours"))) + " 小时");
        } else {
            addLog(task, "info", "加班合计 " + TextUtils.numberText(number(result.stats.get("total_hours"))) + " 小时");
        }
    }

    private double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0d;
    }

    private Map<String, Object> snapshot(CreateTaskRequest request, IntentResult plan) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("brief", request.brief());
        snapshot.put("scene", plan.scene);
        snapshot.put("scene_name", plan.sceneName);
        snapshot.put("parser", plan.parser);
        snapshot.put("confidence", Math.round(plan.confidence * 100) / 100d);
        snapshot.put("join_keys", plan.joinKeys);
        snapshot.put("filters", plan.filters);
        snapshot.put("target_template", plan.targetTemplate.name());
        snapshot.put("template_file_id", request.config() == null ? null : request.config().templateFileId());
        snapshot.put("output_columns", plan.targetTemplate.columns());
        snapshot.put("isolate_anomaly", request.config() == null || request.config().isolateAnomaly() == null
                || request.config().isolateAnomaly());
        snapshot.put("strict_format", request.config() != null && Boolean.TRUE.equals(request.config().strictFormat()));
        List<Map<String, Object>> mapping = new ArrayList<>();
        if (request.config() != null && request.config().mapping() != null) {
            for (MappingEntry entry : request.config().mapping()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("target", entry.target());
                item.put("file_id", entry.fileId());
                item.put("column", entry.column());
                // 拼接规则也要落盘，否则“重新执行”会把表达式丢掉
                item.put("expr", entry.expr());
                mapping.add(item);
            }
        }
        snapshot.put("mapping", mapping);
        return snapshot;
    }

    private void addStep(TaskRecord task, AgentStep step) {
        step.index = task.agentSteps.size() + 1;
        task.agentSteps.add(step);
    }

    private void addLog(TaskRecord task, String level, String message) {
        task.logs.add(new TaskRecord.LogEntry(TimeUtils.clock(), level, message));
    }

    // 把结果表归档进「我的仓库」。归档失败不影响任务本身，只在任务日志里提示一句，任务照样算跑完
    private void archive(TaskRecord task) {
        try {
            Map<String, Object> sheet = sheetService.archiveFromTask(task);
            addLog(task, "info", "结果已归档到「我的仓库」：" + sheet.get("name"));
        } catch (Exception e) {
            log.warn("任务 {} 归档到表格仓库失败：{}", task.id, e.getMessage());
            addLog(task, "warn", "归档到「我的仓库」失败：" + e.getMessage());
        }
    }

    private void finish(TaskRecord task, long started) {
        task.durationMs = System.currentTimeMillis() - started;
        task.finishedAt = TimeUtils.nowSecond();
        store.put(task);
    }
}
