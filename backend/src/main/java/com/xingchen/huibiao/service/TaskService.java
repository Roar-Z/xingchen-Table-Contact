package com.xingchen.huibiao.service;

import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.dto.ApiRequests.AnalyzeRequest;
import com.xingchen.huibiao.dto.ApiRequests.CreateTaskRequest;
import com.xingchen.huibiao.dto.ApiRequests.FileRef;
import com.xingchen.huibiao.dto.ApiRequests.MappingEntry;
import com.xingchen.huibiao.dto.ApiRequests.PreviewRequest;
import com.xingchen.huibiao.dto.ApiRequests.TaskConfig;
import com.xingchen.huibiao.model.FileMeta;
import com.xingchen.huibiao.model.IntentResult;
import com.xingchen.huibiao.model.ProcessResult;
import com.xingchen.huibiao.model.ResultRevision;
import com.xingchen.huibiao.model.TaskRecord;
import com.xingchen.huibiao.model.TemplateItem;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// 任务相关的业务入口。试算和正式执行走同一套服务，避免“预览能过、执行报错”的尴尬。
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final IntentService intent;
    private final SceneRouter router;
    private final TaskRunner runner;
    private final TaskStore store;
    private final FileService files;
    private final SettingsService settings;
    private final DataPaths paths;
    private final ResultEditService edits;

    public TaskService(IntentService intent, SceneRouter router, TaskRunner runner, TaskStore store,
                       FileService files, SettingsService settings, DataPaths paths,
                       ResultEditService edits) {
        this.intent = intent;
        this.router = router;
        this.runner = runner;
        this.store = store;
        this.files = files;
        this.settings = settings;
        this.paths = paths;
        this.edits = edits;
    }

    public IntentResult analyze(AnalyzeRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("先选至少一个源表");
        }
        return intent.analyze(request.brief(), request.files(), request.templateFileId());
    }

    public Map<String, Object> preview(PreviewRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("先选至少一个源表");
        }
        long started = System.currentTimeMillis();
        String templateFileId = request.config() == null ? null : request.config().templateFileId();
        IntentResult plan = intent.analyze(request.brief(), request.files(), templateFileId);
        plan = router.align(plan, request.config());
        ProcessResult result = router.process(request.files(), plan, request.config());
        long elapsed = System.currentTimeMillis() - started;

        List<String> warnings = new ArrayList<>(result.warnings);
        double ratio = result.totalRows == 0 ? 0 : result.anomalyRows * 100d / result.totalRows;
        double threshold = settings.numberRule("anomaly_threshold", 20);
        if (result.anomalyRows > 0 && ratio > threshold) {
            warnings.add(String.format("异常占比 %.1f%%，超过设置的 %.0f%%，正式执行后会标记为待复核。", ratio, threshold));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elapsed_ms", elapsed);
        out.put("scene", plan.scene);
        out.put("scene_name", plan.sceneName);
        out.put("target", plan.targetTemplate.name());
        out.put("join_keys", plan.joinKeys);
        out.put("filters", plan.filters);
        out.put("stats", statCards(result, plan.scene));
        out.put("output_columns", result.outputColumns);
        out.put("preview", result.preview);
        out.put("total_preview", result.outputRows);
        out.put("output_rows", result.outputRows);
        out.put("anomaly_rows", result.anomalyRows);
        out.put("anomalies", result.anomalies);
        out.put("warnings", warnings);
        out.put("rules", result.rules);
        return out;
    }

    public TaskRecord create(CreateTaskRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("先选至少一个源表");
        }
        String scene = sceneOf(request);
        String templateFileId = request.config() == null ? null : request.config().templateFileId();
        TemplateItem template = settings.builtIn("leave".equals(scene) ? "tpl_absence" : "tpl_overtime");
        if (templateFileId != null && !templateFileId.isBlank()) {
            // 上传了模板就以它的列和名字为准，任务详情里也显示真实模板名
            template = files.find(templateFileId)
                    .map(meta -> new TemplateItem("tpl_uploaded", meta.name, scene, false,
                            "上传的目标模板，导出文件按它的表头生成。", meta.sheets.isEmpty()
                            ? new ArrayList<>() : new ArrayList<>(meta.sheets.get(0).columns)))
                    .orElse(template);
        }

        TaskRecord task = new TaskRecord();
        task.id = store.nextId();
        task.brief = request.brief() == null ? "" : request.brief().trim();
        task.scene = scene;
        task.sceneName = "leave".equals(scene) ? "假期余量结算" : "加班记录桥接";
        task.name = nameOf(request.name(), task.sceneName);
        task.status = "queued";
        task.progress = 5;
        task.createdAt = TimeUtils.nowSecond();
        task.target = template == null ? task.sceneName : template.name();
        task.outputColumns = template == null ? new ArrayList<>() : new ArrayList<>(template.columns());

        for (FileRef ref : request.files()) {
            files.find(ref.fileId()).ifPresent(meta -> {
                int rows = meta.sheets.isEmpty() ? 0 : meta.sheets.get(0).rows;
                String role = ref.role() == null || ref.role().isBlank() ? meta.role : ref.role();
                task.sourceFiles.add(new TaskRecord.SourceRef(meta.fileId, meta.name, roleLabel(role), rows));
            });
        }
        if (task.sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("源文件记录已经失效，请重新上传");
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("brief", task.brief);
        config.put("scene", scene);
        config.put("target_template", task.target);
        config.put("source_files", task.sourceFiles.stream().map(f -> f.name).toList());
        config.put("isolate_anomaly", isolateAnomaly(request));
        config.put("strict_format", strictFormat(request));
        task.config = config;
        task.logs.add(new TaskRecord.LogEntry(TimeUtils.clock(), "info", "任务已进入队列"));

        store.put(task);
        runner.run(task.id, request);
        log.info("新建任务 {}（{}）", task.id, task.name);
        return task;
    }

    public Map<String, Object> list(String status, String keyword, int page, int pageSize) {
        List<TaskRecord> all = store.all();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("all", all.size());
        counts.put("done", (int) all.stream().filter(t -> "done".equals(t.status)).count());
        counts.put("running", (int) all.stream().filter(t -> "running".equals(t.status) || "queued".equals(t.status)).count());
        counts.put("warning", (int) all.stream().filter(t -> "warning".equals(t.status)).count());
        counts.put("failed", (int) all.stream().filter(t -> "failed".equals(t.status)).count());

        List<TaskRecord> filtered = new ArrayList<>(all);
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            if ("running".equals(status)) {
                filtered.removeIf(t -> !"running".equals(t.status) && !"queued".equals(t.status));
            } else {
                filtered.removeIf(t -> !status.equals(t.status));
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.trim().toLowerCase(Locale.ROOT);
            filtered.removeIf(t -> !(t.name != null && t.name.toLowerCase(Locale.ROOT).contains(q))
                    && !(t.id != null && t.id.toLowerCase(Locale.ROOT).contains(q)));
        }

        int size = Math.max(1, Math.min(pageSize, 100));
        int current = Math.max(1, page);
        int from = Math.min(filtered.size(), (current - 1) * size);
        int to = Math.min(filtered.size(), from + size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (TaskRecord task : filtered.subList(from, to)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", task.id);
            item.put("name", task.name);
            item.put("scene", task.scene);
            item.put("scene_name", task.sceneName);
            item.put("status", task.status);
            item.put("progress", task.progress);
            item.put("source_count", task.sourceFiles.size());
            item.put("total_rows", task.totalRows);
            item.put("output_rows", task.outputRows);
            item.put("anomaly_rows", task.anomalyRows);
            item.put("duration_ms", task.durationMs);
            item.put("created_at", task.createdAt);
            items.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", filtered.size());
        out.put("page", current);
        out.put("page_size", size);
        out.put("counts", counts);
        out.put("items", items);
        return out;
    }

    public Optional<TaskRecord> find(String id) {
        return store.find(id);
    }

    // HR 人工看过异常日志后确认结果可用，把待复核改成已完成。
    public TaskRecord confirmReview(String id) {
        TaskRecord task = store.find(id)
                .orElseThrow(() -> new IllegalArgumentException("没有找到任务 " + id));
        if (!"warning".equals(task.status)) {
            throw new IllegalArgumentException("只有待复核的任务才能确认复核");
        }
        task.status = "done";
        task.progress = 100;
        task.logs.add(new TaskRecord.LogEntry(TimeUtils.clock(), "ok", "已人工复核，异常日志确认无误，结果可以交给 HR 导入"));
        store.put(task);
        log.info("任务 {} 已人工复核，标记为已完成", id);
        return task;
    }

    // 用当前任务保存的源文件和配置重新跑一遍，成功与否由自动判定写回状态。
    public TaskRecord rerun(String id) {
        TaskRecord task = store.find(id)
                .orElseThrow(() -> new IllegalArgumentException("没有找到任务 " + id));
        assertIdle(task, "任务正在执行，等它跑完再重新执行");
        rerunWith(task, "重新执行，沿用原源文件和配置");
        log.info("任务 {} 重新执行", id);
        return task;
    }

    // 用一句话改结果表。
    public Map<String, Object> revise(String id, String instruction) {
        TaskRecord task = store.find(id)
                .orElseThrow(() -> new IllegalArgumentException("没有找到任务 " + id));
        assertIdle(task, "任务正在执行，等它跑完再改结果");
        if (!"done".equals(task.status) && !"warning".equals(task.status)) {
            throw new IllegalArgumentException("这个任务还没有可用结果，先让它跑成功再改");
        }
        List<Map<String, Object>> revisions = edits.revisionsOf(task);
        if (revisions.size() >= ResultEditService.MAX_REVISIONS) {
            throw new IllegalArgumentException("修改记录已经到上限（" + ResultEditService.MAX_REVISIONS
                    + " 条），先撤销几条再改");
        }
        ResultRevision revision = edits.parse(instruction, task);
        revisions.add(edits.toMap(revision));
        task.config.put("edits", revisions);
        task.logs.add(new TaskRecord.LogEntry(TimeUtils.clock(), "info", "按你的话改结果：" + revision.summary));
        store.put(task);
        rerunWith(task, "按 " + revisions.size() + " 条修改重新计算并导出");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revision", revision);
        out.put("task", task);
        log.info("任务 {} 应用自然语言修改：{}", id, revision.summary);
        return out;
    }

    // 撤销最近一条自然语言修改。修改记录顺序敏感（比如先排序再改第 3 行），所以只能倒着撤。
    public TaskRecord undoRevision(String id) {
        TaskRecord task = store.find(id)
                .orElseThrow(() -> new IllegalArgumentException("没有找到任务 " + id));
        assertIdle(task, "任务正在执行，等它跑完再撤销");
        List<Map<String, Object>> revisions = edits.revisionsOf(task);
        if (revisions.isEmpty()) {
            throw new IllegalArgumentException("这个任务还没有自然语言修改可以撤销");
        }
        Map<String, Object> removed = revisions.remove(revisions.size() - 1);
        if (revisions.isEmpty()) {
            task.config.remove("edits");
        } else {
            task.config.put("edits", revisions);
        }
        task.logs.add(new TaskRecord.LogEntry(TimeUtils.clock(), "info",
                "撤销一条修改：" + text(removed.get("summary"))));
        store.put(task);
        rerunWith(task, "已撤销最近一条修改，重新计算并导出");
        return task;
    }

    private void rerunWith(TaskRecord task, String message) {
        List<FileRef> refs = new ArrayList<>();
        for (TaskRecord.SourceRef source : task.sourceFiles) {
            FileMeta meta = files.find(source.fileId)
                    .orElseThrow(() -> new IllegalArgumentException("源文件记录已失效：" + source.name));
            if (!Files.isRegularFile(paths.uploadPath(meta.storedName))) {
                throw new IllegalArgumentException("源文件已经不在 uploads 目录：" + source.name);
            }
            // 不覆盖 role，交给文件登记里的原始角色决定
            refs.add(new FileRef(source.fileId, null));
        }
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("这个任务没有可用的源文件，请重新上传");
        }

        TaskConfig config = new TaskConfig(task.scene, mappingOf(task), templateFileIdOf(task),
                boolConfig(task, "isolate_anomaly", true), boolConfig(task, "strict_format", false));
        resetForRun(task);
        task.logs.add(new TaskRecord.LogEntry(TimeUtils.clock(), "info", message));
        store.put(task);
        runner.run(task.id, new CreateTaskRequest(task.name, task.brief, refs, config));
    }

    private void assertIdle(TaskRecord task, String message) {
        if ("queued".equals(task.status) || "running".equals(task.status)) {
            throw new IllegalArgumentException(message);
        }
    }

    // 删除任务记录和它的产出目录，源文件保留给其它任务复用。
    public int delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("先选中要删除的任务");
        }
        List<TaskRecord> targets = new ArrayList<>();
        for (String id : ids) {
            store.find(id).ifPresent(targets::add);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("选中的任务已经不存在");
        }
        for (TaskRecord task : targets) {
            if ("queued".equals(task.status) || "running".equals(task.status)) {
                throw new IllegalArgumentException("任务 " + task.id + " 正在执行，等它跑完再删除");
            }
        }
        int removed = store.removeAll(targets.stream().map(t -> t.id).toList());
        targets.forEach(t -> deleteOutputs(t.id));
        log.info("删除任务 {} 个", removed);
        return removed;
    }

    // 「删除所有表格数据」时一起清任务记录：任务和它产出的表格是一套东西，
    public int clearAll() {
        List<String> ids = store.all().stream()
                .map(task -> task.id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return delete(ids);
    }

    private void deleteOutputs(String taskId) {
        Path dir = paths.taskOutputDir(taskId);
        if (!dir.startsWith(paths.outputs()) || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("产出文件删除失败 {}：{}", path, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("产出目录清理失败 {}：{}", dir, e.getMessage());
        }
    }

    private void resetForRun(TaskRecord task) {
        task.status = "queued";
        task.progress = 5;
        task.error = null;
        task.finishedAt = null;
        task.durationMs = 0;
        task.totalRows = 0;
        task.outputRows = 0;
        task.anomalyRows = 0;
        task.preview = new ArrayList<>();
        task.anomalies = new ArrayList<>();
        task.warnings = new ArrayList<>();
        task.artifacts = new ArrayList<>();
        task.stats = new LinkedHashMap<>();
        task.agentSteps = new ArrayList<>();
    }

    private List<MappingEntry> mappingOf(TaskRecord task) {
        List<MappingEntry> mapping = new ArrayList<>();
        Object raw = task.config.get("mapping");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String target = text(map.get("target"));
                    String fileId = text(map.get("file_id"));
                    String column = text(map.get("column"));
                    String expr = text(map.get("expr"));
                    // 普通映射要文件 + 列；拼接映射只要有表达式就算一条
                    if (target != null && ((fileId != null && column != null) || expr != null)) {
                        mapping.add(new MappingEntry(target, fileId, column, expr));
                    }
                }
            }
        }
        return mapping;
    }

    // 老记录没存 template_file_id 时，按目标模板名去文件索引里找回上传的模板。
    private String templateFileIdOf(TaskRecord task) {
        String stored = text(task.config.get("template_file_id"));
        if (stored != null) {
            return stored;
        }
        if (task.target == null || task.target.isBlank()) {
            return null;
        }
        return files.all().stream()
                .filter(meta -> "target_template".equals(meta.role) && task.target.equals(meta.name))
                .map(meta -> meta.fileId)
                .findFirst()
                .orElse(null);
    }

    private boolean boolConfig(TaskRecord task, String key, boolean fallback) {
        Object value = task.config.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    // 下载时只认任务记录里登记过的文件名，避免路径穿越。
    public Path artifactPath(String taskId, String artifactName) {
        TaskRecord task = store.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到任务 " + taskId));
        String wanted = TextUtils.safeName(artifactName);
        TaskRecord.Artifact artifact = task.artifacts.stream()
                .filter(a -> a.name.equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("这个任务没有产出文件：" + artifactName));
        Path path = resolveArtifact(artifact.path);
        if (!path.startsWith(paths.root())) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("产出文件已经被清理，请重新执行任务");
        }
        return path;
    }

    // 产出路径对外写成 data/outputs/...（从 backend 目录算起），
    private Path resolveArtifact(String relative) {
        String clean = relative == null ? "" : relative.replace('\\', '/');
        Path parent = paths.root().getParent();
        if (parent != null) {
            Path candidate = parent.resolve(clean).normalize();
            if (candidate.startsWith(paths.root())) {
                return candidate;
            }
        }
        return paths.root().resolve(clean).normalize();
    }

    private String nameOf(String name, String sceneName) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return sceneName + " · " + LocalDate.now().format(MONTH_DAY);
    }

    private boolean isolateAnomaly(CreateTaskRequest request) {
        return request.config() == null || request.config().isolateAnomaly() == null
                || request.config().isolateAnomaly();
    }

    private boolean strictFormat(CreateTaskRequest request) {
        return request.config() != null && Boolean.TRUE.equals(request.config().strictFormat());
    }

    private String sceneOf(CreateTaskRequest request) {
        if (request.config() != null && request.config().scene() != null && !request.config().scene().isBlank()) {
            return request.config().scene();
        }
        for (FileRef ref : request.files()) {
            FileMeta meta = files.find(ref.fileId()).orElse(null);
            if (meta == null) {
                continue;
            }
            String role = ref.role() == null || ref.role().isBlank() ? meta.role : ref.role();
            if ("overtime".equals(role)) {
                return "overtime";
            }
        }
        return "leave";
    }

    private String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "leave_request" -> "OA 请假单";
            case "annual_balance" -> "年假余额";
            case "overtime" -> "ERP 加班表";
            case "target_template" -> "HR 目标模板";
            default -> "普通源表";
        };
    }

    private List<Map<String, Object>> statCards(ProcessResult result, String scene) {
        List<String[]> defs = "leave".equals(scene)
                ? List.of(
                        new String[]{"source_rows", "源表行数", "行"},
                        new String[]{"candidate_rows", "命中条件", "行"},
                        new String[]{"output_rows", "输出行数", "行"},
                        new String[]{"annual_hours", "年假抵扣", "小时"},
                        new String[]{"personal_hours", "事假合计", "小时"},
                        new String[]{"anomaly_rows", "异常隔离", "行"})
                : List.of(
                        new String[]{"source_rows", "源表行数", "行"},
                        new String[]{"candidate_rows", "命中条件", "行"},
                        new String[]{"output_rows", "输出行数", "行"},
                        new String[]{"total_hours", "加班合计", "小时"},
                        new String[]{"skipped_rows", "条件外跳过", "行"},
                        new String[]{"anomaly_rows", "异常隔离", "行"});

        List<Map<String, Object>> cards = new ArrayList<>();
        for (String[] def : defs) {
            if (!result.stats.containsKey(def[0])) {
                continue;
            }
            Object raw = result.stats.get(def[0]);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("key", def[0]);
            card.put("label", def[1]);
            card.put("value", display(raw));
            card.put("unit", def[2]);
            card.put("warn", "anomaly_rows".equals(def[0]) && result.anomalyRows > 0);
            cards.add(card);
        }
        return cards;
    }

    private Object display(Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return String.format(Locale.CHINA, "%,d", (long) Math.rint(value));
            }
            return TextUtils.numberText(value);
        }
        return raw == null ? "" : raw;
    }
}
