package com.xingchen.huibiao.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// 统一管理数据目录，避免每个服务各自 new 一个相对路径。
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

    // 老版本的任务文件，只在首次启动做一次性导入时用。
    public Path tasksFile() {
        return root.resolve("tasks.json");
    }

    // 老版本的映射方案文件，只在首次启动做一次性导入时用；现在存 huibiao.db 的 kv 表。
    public Path mappingSchemesFile() {
        return root.resolve("mapping-schemes.json");
    }

    // 老版本的经验记忆文件，只在首次启动做一次性导入时用；现在存 huibiao.db 的 kv 表。
    public Path agentExperienceFile() {
        return root.resolve("agent-experience.json");
    }

    // 老版本的文件索引，只在首次启动做一次性导入时用；现在存 huibiao.db 的 kv 表。
    public Path filesFile() {
        return root.resolve("files.json");
    }

    // 老版本的设置文件，只在首次启动做一次性导入时用；现在存 huibiao.db 的 kv 表。
    public Path settingsFile() {
        return root.resolve("settings.json");
    }

    // 老版本的运行观测文件，只在首次启动做一次性导入时用；现在存 agent_runs 表。
    public Path agentRunsFile() {
        return root.resolve("agent-runs.json");
    }

    // 老版本的审计日志，只在首次启动做一次性导入时用；现在存 audit_logs 表。
    public Path auditLogFile() {
        return root.resolve("audit.log.jsonl");
    }

    public Path uploadPath(String storedName) {
        return uploads.resolve(storedName).normalize();
    }

    public Path taskOutputDir(String taskId) {
        return outputs.resolve(taskId).normalize();
    }

    // 表格智作的仓库：每张表一个 xlsx；清单、列定义和日志都存 huibiao.db 的 sheets / sheet_logs 表。
    public Path sheets() {
        return sheets;
    }

    // 老版本的表格清单，只在首次启动做一次性导入时用；现在存 sheets 表。
    public Path sheetsFile() {
        return root.resolve("sheets.json");
    }

    // 老版本的已删除表格日志，只在首次启动做一次性导入时用；现在以 deleted=1 记在 sheet_logs 表里。
    public Path sheetLogsFile() {
        return root.resolve("sheet-logs.json");
    }

    public Path sheetPath(String sheetId) {
        return sheets.resolve(sheetId + ".xlsx").normalize();
    }

    // 撤销用的快照，只在最近一次修改前留一份。
    public Path sheetBackupPath(String sheetId) {
        return sheetBackup.resolve(sheetId + ".xlsx").normalize();
    }

    // 撤销快照所在的目录，「删除所有表格数据」时连快照一起清掉，不然删完还能被撤销拿回来。
    public Path sheetBackup() {
        return sheetBackup;
    }

    // 智能体模式的默认导出目录：前端工程根目录下的 downloads。
    public Path downloadsRoot() {
        Path frontend = Paths.get(properties.getFrontendDir()).toAbsolutePath().normalize();
        Path name = frontend.getFileName();
        Path root = name != null && "dist".equalsIgnoreCase(name.toString()) && frontend.getParent() != null
                ? frontend.getParent() : frontend;
        return root.resolve("downloads");
    }
}
