package com.xingchen.huibiao.service;

import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.Anomaly;
import com.xingchen.huibiao.model.ProcessResult;
import com.xingchen.huibiao.model.TaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 写产出文件。每个任务一个目录，文件名照业务人员熟悉的写法来，比如 HR导入_加班_20261030.xls
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final DataPaths paths;
    private final ExcelService excel;

    public ExportService(DataPaths paths, ExcelService excel) {
        this.paths = paths;
        this.excel = excel;
    }

    public List<TaskRecord.Artifact> write(TaskRecord task, ProcessResult result, String scene) {
        String date = LocalDate.now().format(DAY);
        String prefix = "leave".equals(scene) ? "HR导入_缺勤_" : "HR导入_加班_";
        String baseName = prefix + date;
        Path dir = paths.taskOutputDir(task.id);
        List<TaskRecord.Artifact> artifacts = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            List<List<Object>> cells = toCells(result);

            Path xls = dir.resolve(baseName + ".xls");
            excel.writeXls(xls, result.outputColumns, cells);
            artifacts.add(artifact(xls, "HR 导入表（.xls）"));

            Path xlsx = dir.resolve(baseName + ".xlsx");
            excel.writeXlsx(xlsx, result.outputColumns, cells);
            artifacts.add(artifact(xlsx, "备份表（.xlsx）"));

            if (!result.anomalies.isEmpty()) {
                Path anomalyFile = dir.resolve("异常日志_" + date + ".xlsx");
                excel.writeXlsx(anomalyFile, List.of("源文件", "行号", "异常类型", "原因", "原始内容"), anomalyCells(result));
                artifacts.add(artifact(anomalyFile, "异常行日志"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("写出结果文件失败：" + e.getMessage(), e);
        }
        log.info("任务 {} 产出 {} 个文件", task.id, artifacts.size());
        return artifacts;
    }

    private List<List<Object>> toCells(ProcessResult result) {
        List<List<Object>> cells = new ArrayList<>();
        for (Map<String, Object> row : result.rows) {
            List<Object> line = new ArrayList<>();
            for (String column : result.outputColumns) {
                line.add(row.get(column));
            }
            cells.add(line);
        }
        return cells;
    }

    private List<List<Object>> anomalyCells(ProcessResult result) {
        List<List<Object>> cells = new ArrayList<>();
        for (Anomaly item : result.anomalies) {
            cells.add(List.of(
                    item.source() == null ? "" : item.source(),
                    item.row(),
                    item.type() == null ? "数据异常" : item.type(),
                    item.reason() == null ? "" : item.reason(),
                    item.raw() == null ? "" : item.raw()
            ));
        }
        return cells;
    }

    private TaskRecord.Artifact artifact(Path file, String label) throws IOException {
        String name = file.getFileName().toString();
        // 对外展示时从 backend 目录算起，和 README、接口文档里的 data/outputs/... 保持一致
        String folder = paths.root().getFileName() == null ? "data" : paths.root().getFileName().toString();
        String relative = folder + "/" + paths.root().relativize(file).toString().replace('\\', '/');
        return new TaskRecord.Artifact(name, label, sizeText(Files.size(file)), relative);
    }

    private String sizeText(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024d / 1024d);
        }
        return String.format("%.1f KB", Math.max(0.1, bytes / 1024d));
    }
}
