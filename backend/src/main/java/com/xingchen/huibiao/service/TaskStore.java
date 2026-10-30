package com.xingchen.huibiao.service;

import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.model.TaskRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 任务仓库。落 SQLite（data/huibiao.db）：
@Service
public class TaskStore {

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);

    private final DataPaths paths;
    private final SqliteDb db;
    private final Map<String, TaskRecord> tasks = new LinkedHashMap<>();

    public TaskStore(DataPaths paths, SqliteDb db) {
        this.paths = paths;
        this.db = db;
    }

    @PostConstruct
    public synchronized void load() {
        migrateLegacy();
        List<TaskRecord> loaded = db.list("SELECT id, body FROM tasks", rs -> {
            TaskRecord record = db.fromJson(rs.getString("body"), TaskRecord.class);
            if (record != null && (record.id == null || record.id.isBlank())) {
                record.id = rs.getString("id");
            }
            return record;
        });
        tasks.clear();
        for (TaskRecord record : loaded) {
            if (record != null && record.id != null) {
                tasks.put(record.id, record);
            }
        }
        log.info("任务仓库已加载 {} 条", tasks.size());
    }

    // 老版本的 tasks.json 只在库里还空着时导入一次，导完把原文件改名留档。
    private void migrateLegacy() {
        Path legacy = paths.tasksFile();
        if (!db.isEmpty("tasks") || !Files.isRegularFile(legacy)) {
            return;
        }
        try {
            List<TaskRecord> loaded = db.jsonList(Files.readString(legacy), TaskRecord.class);
            db.tx(connection -> {
                for (TaskRecord record : loaded) {
                    upsert(connection, record);
                }
                return null;
            });
            log.info("已把 {} 条旧任务导入数据库", loaded.size());
            db.retireLegacy(legacy);
        } catch (Exception e) {
            log.warn("旧任务导入失败，先按空仓库启动：{}", e.getMessage());
        }
    }

    public synchronized void save() {
        db.tx(connection -> {
            for (TaskRecord task : tasks.values()) {
                upsert(connection, task);
            }
            return null;
        });
    }

    private void upsert(Connection connection, TaskRecord task) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tasks (id, name, status, created_at, finished_at, body) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET name = excluded.name, status = excluded.status, "
                        + "created_at = excluded.created_at, finished_at = excluded.finished_at, body = excluded.body")) {
            ps.setString(1, task.id);
            ps.setString(2, task.name);
            ps.setString(3, task.status);
            ps.setString(4, task.createdAt);
            ps.setString(5, task.finishedAt);
            ps.setString(6, db.toJson(task));
            ps.executeUpdate();
        }
    }

    public synchronized List<TaskRecord> all() {
        List<TaskRecord> copy = new ArrayList<>(tasks.values());
        copy.sort(Comparator.comparing((TaskRecord t) -> t.createdAt == null ? "" : t.createdAt).reversed());
        return copy;
    }

    public synchronized Optional<TaskRecord> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public synchronized void put(TaskRecord task) {
        tasks.put(task.id, task);
        save();
    }

    // 批量删除，一个事务里删完。返回真正删掉的条数。
    public synchronized int removeAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int before = tasks.size();
        List<String> removedIds = new ArrayList<>();
        for (String id : ids) {
            if (tasks.remove(id) != null) {
                removedIds.add(id);
            }
        }
        if (removedIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(removedIds.size(), "?"));
        db.update("DELETE FROM tasks WHERE id IN (" + placeholders + ")", removedIds.toArray());
        return before - tasks.size();
    }

    public synchronized String nextId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "T" + date + "-";
        int max = 0;
        for (TaskRecord task : tasks.values()) {
            if (task.id != null && task.id.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(task.id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 编号被手动改过就不参与计数
                }
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    public synchronized int count() {
        return tasks.size();
    }
}
