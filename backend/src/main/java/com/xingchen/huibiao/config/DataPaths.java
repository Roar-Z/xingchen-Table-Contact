package com.xingchen.huibiao.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Component
public class DataPaths {

    private final AppProperties properties;
    private Path root;
    private Path uploads;
    private Path outputs;
    private Path sheets;
    private Path sheetBackup;

    public DataPaths(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        root = Paths.get(properties.getDataDir()).toAbsolutePath().normalize();
        uploads = root.resolve("uploads");
        outputs = root.resolve("outputs");
        sheets = root.resolve("sheets");
        sheetBackup = root.resolve("sheet-backup");
        try {
            Files.createDirectories(uploads);
            Files.createDirectories(outputs);
            Files.createDirectories(sheets);
            Files.createDirectories(sheetBackup);
        } catch (IOException e) {
            throw new IllegalStateException("创建数据目录失败：" + root, e);
        }
    }

    public Path root() {
        return root;
    }

    public Path uploads() {
        return uploads;
    }

    public Path outputs() {
        return outputs;
    }

    // 本地唯一的数据文件：data/huibiao.db。
    public Path dbFile() {
        return root.resolve("huibiao.db");
    }

    public Path tasksFile() {
        return root.resolve("tasks.json");
    }

    // 映射方案文件
    public Path mappingSchemesFile() {
        return root.resolve("mapping-schemes.json");
    }

    public Path agentExperienceFile() {
        return root.resolve("agent-experience.json");
    }

    public Path filesFile() {
        return root.resolve("files.json");
    }

    public Path settingsFile() {
        return root.resolve("settings.json");
    }

    public Path agentRunsFile() {
        return root.resolve("agent-runs.json");
    }

    public Path auditLogFile() {
        return root.resolve("audit.log.jsonl");
    }

    public Path uploadPath(String storedName) {
        return uploads.resolve(storedName).normalize();
    }

    public Path taskOutputDir(String taskId) {
        return outputs.resolve(taskId).normalize();
    }

    // 表格智作的仓库：每张表一个 xlsx；清单、列定义和日志在 huibiao.db 的 sheets / sheet_logs 表里
    public Path sheets() {
        return sheets;
    }

    public Path sheetsFile() {
        return root.resolve("sheets.json");
    }

    public Path sheetLogsFile() {
        return root.resolve("sheet-logs.json");
    }

    public Path sheetPath(String sheetId) {
        return sheets.resolve(sheetId + ".xlsx").normalize();
    }

    // 撤销用的快照
    public Path sheetBackupPath(String sheetId) {
        return sheetBackup.resolve(sheetId + ".xlsx").normalize();
    }

    // 撤销快照目录
    public Path sheetBackup() {
        return sheetBackup;
    }

    // 智能体模式的默认导出目录
    public Path downloadsRoot() {
        Path frontend = Paths.get(properties.getFrontendDir()).toAbsolutePath().normalize();
        Path name = frontend.getFileName();
        Path root = name != null && "dist".equalsIgnoreCase(name.toString()) && frontend.getParent() != null
                ? frontend.getParent() : frontend;
        return root.resolve("downloads");
    }
}
