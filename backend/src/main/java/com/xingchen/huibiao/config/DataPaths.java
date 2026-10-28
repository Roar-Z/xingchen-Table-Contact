package com.xingchen.huibiao.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 统一管理数据目录，避免每个服务各自 new 一个相对路径。
 */
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

    public Path tasksFile() {
        return root.resolve("tasks.json");
    }

    /**
     * 映射方案独立存一个文件，和任务记录分开，方便直接拷贝给同事复用。
     */
    public Path mappingSchemesFile() {
        return root.resolve("mapping-schemes.json");
    }

    /**
     * 智能体的经验记忆：成功的场景 + 模板 + 映射组合，下次同类表优先复用。
     */
    public Path agentExperienceFile() {
        return root.resolve("agent-experience.json");
    }

    public Path filesFile() {
        return root.resolve("files.json");
    }

    public Path settingsFile() {
        return root.resolve("settings.json");
    }

    /**
     * 智能体运行观测：每次配置编排的耗时、token、工具调用、兜底情况，供运行面板统计。
     */
    public Path agentRunsFile() {
        return root.resolve("agent-runs.json");
    }

    /**
     * 审计日志：方案生成、任务创建/确认/改结果/删除等关键动作，一行一条，只追加不改写。
     */
    public Path auditLogFile() {
        return root.resolve("audit.log.jsonl");
    }

    public Path uploadPath(String storedName) {
        return uploads.resolve(storedName).normalize();
    }

    public Path taskOutputDir(String taskId) {
        return outputs.resolve(taskId).normalize();
    }

    /**
     * 表格智作的仓库：每张表一个 xlsx，元数据和操作日志放在 sheets.json。
     */
    public Path sheets() {
        return sheets;
    }

    public Path sheetsFile() {
        return root.resolve("sheets.json");
    }

    public Path sheetPath(String sheetId) {
        return sheets.resolve(sheetId + ".xlsx").normalize();
    }

    /**
     * 撤销用的快照，只在最近一次修改前留一份。
     */
    public Path sheetBackupPath(String sheetId) {
        return sheetBackup.resolve(sheetId + ".xlsx").normalize();
    }
}
