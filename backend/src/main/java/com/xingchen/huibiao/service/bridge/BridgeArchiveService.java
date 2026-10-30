package com.xingchen.huibiao.service.bridge;

import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.AgentStep;
import com.xingchen.huibiao.model.Anomaly;
import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.model.TaskRecord;
import com.xingchen.huibiao.service.TaskStore;
import com.xingchen.huibiao.service.sheet.SheetService;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 智能体模式跑完交付后，把这一单同步记进「任务记录」，结果表收进「我的仓库」。
// 只在数据集真正交付（status=done）时才写；只出方案、等人审批的不算一单。
@Service
public class BridgeArchiveService {

    private static final Logger log = LoggerFactory.getLogger(BridgeArchiveService.class);

    private final BridgeStore store;
    private final DataPaths paths;
    private final TaskStore tasks;
    private final SheetService sheets;

    public BridgeArchiveService(BridgeStore store, DataPaths paths, TaskStore tasks, SheetService sheets) {
        this.store = store;
        this.paths = paths;
        this.tasks = tasks;
        this.sheets = sheets;
    }

    // 启动时补一次：加这个联动之前跑完的数据集也要出现在任务记录里，不用用户再跑一遍
    @PostConstruct
    public void backfill() {
        try {
            for (BridgeDataset dataset : store.recent()) {
                if (!"done".equals(dataset.status) || findTask(dataset.id) != null) {
                    continue;
                }
                archive(dataset);
            }
        } catch (Exception e) {
            log.warn("补记历史数据集失败（不影响正常启动）：{}", e.getMessage());
        }
    }

    // 把一单数据集落成任务记录，并把结果表归档进表格仓库。
    // 同一个数据集重复跑时沿用同一条任务、同一张表，不会每跑一次就多一份。
    public TaskRecord archive(BridgeDataset dataset) {
        if (dataset == null || dataset.result == null || !"done".equals(dataset.status)
                || dataset.result.artifacts == null || dataset.result.artifacts.isEmpty()) {
            return null;
        }
        try {
            TaskRecord task = findTask(dataset.id);
            // 只是问了一句、没有重跑时结果还是那一份，不用重复写任务和仓库日志
            if (task != null && sameResult(task, dataset)) {
                return task;
            }
            if (task == null) {
                task = new TaskRecord();
                task.id = tasks.nextId();
                task.createdAt = TextUtils.isBlank(dataset.createdAt) ? TimeUtils.nowSecond() : dataset.createdAt;
            }
            fill(task, dataset);
            tasks.put(task);
            try {
                sheets.archiveFromTask(task);
            } catch (Exception e) {
                // 任务记录已经有结果了，仓库这边失败只提示，不把整单算成失败
                log.warn("数据集 {} 归档到表格仓库失败：{}", dataset.id, e.getMessage());
            }
            log.info("智能体数据集 {} 已记入任务记录 {}（{} 行，异常 {} 行）",
                    dataset.id, task.id, task.outputRows, task.anomalyRows);
            return task;
        } catch (Exception e) {
            log.warn("数据集 {} 写进任务记录失败：{}", dataset.id, e.getMessage());
            return null;
        }
    }

    // 完成时间和行数都对得上，说明这一份结果已经记过了
    private boolean sameResult(TaskRecord task, BridgeDataset dataset) {
        BridgeDataset.Result result = dataset.result;
        return TextUtils.norm(task.finishedAt).equals(TextUtils.norm(result.finishedAt))
                && task.outputRows == result.okRows
                && task.anomalyRows == result.anomalyRows;
    }

    // 数据集号存在配置里，重复交付时按它找同一条任务
    private TaskRecord findTask(String datasetId) {
        for (TaskRecord task : tasks.all()) {
            Object value = task.config == null ? null : task.config.get("bridge_dataset_id");
            if (datasetId != null && datasetId.equals(String.valueOf(value))) {
                return task;
            }
        }
        return null;
    }

    private void fill(TaskRecord task, BridgeDataset dataset) {
        BridgeDataset.Result result = dataset.result;
        BridgeDataset.Plan plan = dataset.plan;
        task.name = title(dataset);
        task.brief = dataset.instruction == null ? "" : dataset.instruction;
        task.scene = "bridge";
        task.sceneName = "跨系统桥接（智能体自主）";
        // 有异常行就按「待复核」处理，和表格智作任务的判定口径一致
        task.status = result.anomalyRows > 0 ? "warning" : "done";
        task.error = null;
        task.progress = 100;
        task.finishedAt = result.finishedAt;
        task.durationMs = result.elapsedMs;
        task.totalRows = result.totalRows;
        task.outputRows = result.okRows;
        task.anomalyRows = result.anomalyRows;
        task.preview = previewOf(result.preview);
        task.outputColumns = result.columns == null ? new ArrayList<>() : new ArrayList<>(result.columns);
        task.warnings = new ArrayList<>();
        task.stats = statsOf(result);
        task.config = configOf(dataset, plan);
        task.sourceFiles = sourcesOf(dataset);
        // 先把交付文件复制到任务产出目录，异常清单的完整明细就从那里读
        task.artifacts = artifactsOf(task.id, dataset);
        task.anomalies = anomaliesOf(task.id, result.anomalyPreview);
        task.logs = logsOf(dataset, result, plan);
        task.agentSteps = stepsOf(plan);
    }

    // 数据集名多半是上传时生成的随机串，这种就按表类型起个能一眼认出来的名字
    private String title(BridgeDataset dataset) {
        String raw = dataset.name == null ? "" : dataset.name.trim();
        if (!raw.isEmpty() && !raw.matches("[0-9a-fA-F-]{32,}")) {
            return raw;
        }
        List<String> kinds = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            String label = TextUtils.isBlank(table.kindLabel) ? table.file : table.kindLabel;
            if (!TextUtils.isBlank(label) && !kinds.contains(label)) {
                kinds.add(label.trim());
            }
        }
        if (kinds.isEmpty()) {
            return "跨系统桥接任务";
        }
        String head = String.join(" + ", kinds.subList(0, Math.min(2, kinds.size())));
        String more = dataset.tables.size() > 2 ? " 等 " + dataset.tables.size() + " 张表" : "";
        return head + more + " 跨系统合并";
    }

    // 数据集的预览是 Map<String,String>，任务记录里统一用 Map<String,Object>
    private List<Map<String, Object>> previewOf(List<Map<String, String>> source) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (source == null) {
            return list;
        }
        for (Map<String, String> row : source) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.putAll(row);
            list.add(item);
        }
        return list;
    }

    private Map<String, Object> statsOf(BridgeDataset.Result result) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("source_rows", result.totalRows);
        stats.put("output_rows", result.okRows);
        stats.put("anomaly_rows", result.anomalyRows);
        stats.put("table_count", result.tableCount);
        stats.put("rounds", result.rounds);
        stats.put("passed", result.passed);
        stats.put("mode", result.mode);
        return stats;
    }

    private Map<String, Object> configOf(BridgeDataset dataset, BridgeDataset.Plan plan) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("bridge_dataset_id", dataset.id);
        config.put("source", "bridge");
        List<String> names = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            names.add(table.file);
        }
        config.put("source_files", names);
        config.put("access", dataset.access);
        if (plan != null) {
            config.put("mode", plan.mode);
            config.put("join_key", plan.joinKey);
            config.put("acceptance", plan.acceptance);
            config.put("plan_summary", plan.summary);
        }
        return config;
    }

    private List<TaskRecord.SourceRef> sourcesOf(BridgeDataset dataset) {
        List<TaskRecord.SourceRef> sources = new ArrayList<>();
        for (BridgeDataset.Table table : dataset.tables) {
            String role = TextUtils.isBlank(table.kindLabel) ? "源表" : table.kindLabel;
            sources.add(new TaskRecord.SourceRef(table.id, table.file, role, table.rows));
        }
        return sources;
    }

    // 异常清单导出的是全量明细（工号/姓名/异常原因/来源表/原值摘要），
    // 数据集里的异常预览只留前几条，任务详情要按全量展示，所以优先读导出文件
    private List<Anomaly> anomaliesOf(String taskId, List<Map<String, String>> preview) {
        List<Anomaly> list = readAnomalySheet(paths.taskOutputDir(taskId).resolve("异常清单.xlsx"));
        if (!list.isEmpty() || preview == null) {
            return list;
        }
        for (int i = 0; i < preview.size(); i++) {
            Map<String, String> row = preview.get(i);
            String reason = value(row, "异常原因");
            String source = value(row, "来源表");
            String raw = value(row, "原值摘要");
            list.add(new Anomaly(i + 1, source, reason, raw, anomalyType(reason)));
        }
        return list;
    }

    private List<Anomaly> readAnomalySheet(Path file) {
        List<Anomaly> list = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return list;
        }
        try (InputStream in = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                return list;
            }
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : header) {
                columns.put(TextUtils.norm(formatter.formatCellValue(cell)), cell.getColumnIndex());
            }
            int reasonAt = column(columns, "异常原因");
            int sourceAt = column(columns, "来源表");
            int rawAt = column(columns, "原值摘要");
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String reason = cellText(row, reasonAt, formatter);
                if (TextUtils.isBlank(reason)) {
                    continue;
                }
                list.add(new Anomaly(list.size() + 1, cellText(row, sourceAt, formatter),
                        reason, cellText(row, rawAt, formatter), anomalyType(reason)));
            }
        } catch (Exception e) {
            log.warn("读取异常清单失败，改用预览快照：{}", e.getMessage());
            return new ArrayList<>();
        }
        return list;
    }

    // 表头叫「异常原因」这种中文名，按归一化后的名字找列，找不到就当没有这一列
    private int column(Map<String, Integer> columns, String name) {
        Integer index = columns.get(TextUtils.norm(name));
        return index == null ? -1 : index;
    }

    private String cellText(Row row, int index, DataFormatter formatter) {
        if (index < 0) {
            return "";
        }
        Cell cell = row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private String anomalyType(String reason) {
        if (reason.contains("对不上") || reason.contains("关联")) return "关联";
        if (reason.contains("为空")) return "必填";
        if (reason.contains("重复")) return "重复";
        if (reason.contains("无法识别") || reason.contains("格式")) return "格式";
        return "校验";
    }

    private String value(Map<String, String> row, String key) {
        String text = row == null ? null : row.get(key);
        return text == null ? "" : text;
    }

    private List<TaskRecord.LogEntry> logsOf(BridgeDataset dataset, BridgeDataset.Result result,
                                             BridgeDataset.Plan plan) {
        List<TaskRecord.LogEntry> logs = new ArrayList<>();
        logs.add(new TaskRecord.LogEntry(TimeUtils.clock(),
                "info", "接收数据集「" + dataset.name + "」，共 " + dataset.tables.size() + " 张表"));
        if (plan != null && !TextUtils.isBlank(plan.summary)) {
            logs.add(new TaskRecord.LogEntry(TimeUtils.clock(), "info", "智能体规划完成：" + plan.summary));
        }
        logs.add(new TaskRecord.LogEntry(TimeUtils.clock(),
                "info", "输出 " + result.okRows + " 行，异常隔离 " + result.anomalyRows + " 行"));
        logs.add(new TaskRecord.LogEntry(TimeUtils.clock(),
                "ok", "任务完成，产出 " + result.artifacts.size() + " 个文件"));
        return logs;
    }

    private List<AgentStep> stepsOf(BridgeDataset.Plan plan) {
        List<AgentStep> steps = new ArrayList<>();
        if (plan == null || plan.steps == null) {
            return steps;
        }
        int index = 1;
        for (BridgeDataset.Step step : plan.steps) {
            String status = "done".equals(step.state) ? "ok" : step.state;
            AgentStep item = new AgentStep("tool", step.title, step.detail, step.tool, status);
            item.index = index++;
            item.elapsedMs = step.ms;
            steps.add(item);
        }
        return steps;
    }

    // 把交付文件复制到任务产出目录，下载、结果预览、归档仓库都按任务这一套路径取
    private List<TaskRecord.Artifact> artifactsOf(String taskId, BridgeDataset dataset) {
        List<TaskRecord.Artifact> list = new ArrayList<>();
        Path target = paths.taskOutputDir(taskId);
        Path source = store.outDir(dataset.id);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new IllegalStateException("创建任务产出目录失败：" + e.getMessage(), e);
        }
        for (String name : dataset.result.artifacts) {
            Path from = source.resolve(name).normalize();
            if (!Files.isRegularFile(from)) {
                continue;
            }
            Path to = target.resolve(name).normalize();
            try {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("复制交付文件 " + name + " 失败：" + e.getMessage(), e);
            }
            list.add(new TaskRecord.Artifact(name, labelOf(name), sizeText(to), relative(to)));
        }
        return list;
    }

    private String labelOf(String name) {
        if (name.contains("异常")) return "异常行日志";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xls")) return "HR 导入表（.xls）";
        return "标准表（.xlsx）";
    }

    // 和表格智作任务一致：对外写成 data/outputs/任务号/文件名
    private String relative(Path file) {
        String folder = paths.root().getFileName() == null ? "data" : paths.root().getFileName().toString();
        return folder + "/" + paths.root().relativize(file).toString().replace('\\', '/');
    }

    private String sizeText(Path file) {
        long bytes;
        try {
            bytes = Files.size(file);
        } catch (IOException e) {
            return "";
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024d / 1024d);
        }
        if (bytes >= 1024) {
            return String.format("%.0f KB", bytes / 1024d);
        }
        return bytes + " B";
    }
}
