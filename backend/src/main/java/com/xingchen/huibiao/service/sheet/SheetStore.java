package com.xingchen.huibiao.service.sheet;

import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.model.SheetRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

// 表格仓库：索引与操作日志存 SQLite（data/huibiao.db），表格本体仍是 data/sheets/{id}.xlsx。
@Service
public class SheetStore {

    private static final Logger log = LoggerFactory.getLogger(SheetStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 仓库所有表格的日志一共只留最近 100 条，跑得越多也不会把索引撑大
    public static final int MAX_LOGS = 100;

    private final DataPaths paths;
    private final SqliteDb db;
    private final Map<String, SheetRecord> items = new LinkedHashMap<>();
    // 已删除表格留下的日志：表没了，但「删除表格」这条要留在操作日志里
    private final List<ArchivedLog> archived = new ArrayList<>();

    // 表被删掉之后仍然要出现在操作日志里的一条记录
    public static class ArchivedLog {
        public String id;
        public String sheetId;
        public String sheetName;
        public String time;
        public String action;
        public String detail;
        public String level = "info";

        public ArchivedLog() {
        }

        public ArchivedLog(String sheetId, String sheetName, String time, String action, String detail, String level) {
            this(sheetId, sheetName, time, action, detail, level, null);
        }

        public ArchivedLog(String sheetId, String sheetName, String time, String action, String detail,
                           String level, String id) {
            this.id = (id == null || id.isBlank()) ? SheetRecord.LogEntry.newId() : id;
            this.sheetId = sheetId;
            this.sheetName = sheetName;
            this.time = time;
            this.action = action;
            this.detail = detail;
            this.level = level;
        }
    }

    public SheetStore(DataPaths paths, SqliteDb db) {
        this.paths = paths;
        this.db = db;
    }

    @PostConstruct
    public synchronized void load() {
        migrateLegacy();
        items.clear();
        for (SheetRecord record : db.list("SELECT * FROM sheets", this::hydrate)) {
            if (record.id != null) {
                items.put(record.id, record);
            }
        }
        reloadLogs();
        log.info("表格仓库已加载 {} 张表、{} 条日志", items.size(), archived.size());
    }

    // 老版本的 sheets.json / sheet-logs.json 只在库里空着时导入一次，导完改名留档。
    private void migrateLegacy() {
        Path sheetsFile = paths.sheetsFile();
        if (db.isEmpty("sheets") && Files.isRegularFile(sheetsFile)) {
            try {
                List<SheetRecord> list = db.jsonList(Files.readString(sheetsFile), SheetRecord.class);
                db.tx(connection -> {
                    for (SheetRecord record : list) {
                        if (record.id == null) {
                            continue;
                        }
                        // 老版本的日志没有 id，这里补上，前端才能按条勾选删除
                        for (SheetRecord.LogEntry entry : record.logs) {
                            if (entry.id == null || entry.id.isBlank()) {
                                entry.id = SheetRecord.LogEntry.newId();
                            }
                        }
                        upsertSheet(connection, record);
                        for (SheetRecord.LogEntry entry : record.logs) {
                            insertLog(connection, record.id, record.name, entry.time, entry.action,
                                    entry.detail, entry.level, false, entry.id);
                        }
                    }
                    trimInTx(connection);
                    return null;
                });
                log.info("已把 {} 张旧表导入数据库", list.size());
                db.retireLegacy(sheetsFile);
            } catch (Exception e) {
                log.warn("旧表格索引导入失败，先按空仓库启动：{}", e.getMessage());
            }
        }

        Path logsFile = paths.sheetLogsFile();
        if (db.count("SELECT COUNT(*) FROM sheet_logs WHERE deleted = 1") == 0 && Files.isRegularFile(logsFile)) {
            try {
                List<ArchivedLog> list = db.jsonList(Files.readString(logsFile), ArchivedLog.class);
                db.tx(connection -> {
                    for (ArchivedLog entry : list) {
                        insertLog(connection, entry.sheetId, entry.sheetName, entry.time, entry.action,
                                entry.detail, entry.level, true, entry.id);
                    }
                    trimInTx(connection);
                    return null;
                });
                db.retireLegacy(logsFile);
            } catch (Exception e) {
                log.warn("旧表日志导入失败，先忽略：{}", e.getMessage());
            }
        }
    }

    private SheetRecord hydrate(ResultSet rs) throws SQLException {
        SheetRecord record = new SheetRecord();
        record.id = rs.getString("id");
        record.name = rs.getString("name");
        record.origin = rs.getString("origin");
        record.originalName = rs.getString("original_name");
        record.taskId = rs.getString("task_id");
        record.note = rs.getString("note");
        record.createdAt = rs.getString("created_at");
        record.updatedAt = rs.getString("updated_at");
        record.columns = db.jsonList(rs.getString("columns"), String.class);
        record.rowCount = rs.getInt("row_count");
        record.revisionCount = rs.getInt("revision_count");
        record.lastSummary = rs.getString("last_summary");
        record.undoAvailable = rs.getInt("undo_available") == 1;
        record.undoRowCount = rs.getInt("undo_row_count");
        record.revisions = db.jsonMapList(rs.getString("revisions"));
        return record;
    }

    // 从库里把日志读回内存：存活表格的日志挂到对应表上，已删除表的日志进归档列表。
    private void reloadLogs() {
        Map<String, List<SheetRecord.LogEntry>> live = new HashMap<>();
        List<ArchivedLog> deleted = new ArrayList<>();
        // 两个集合同一次遍历收集：deleted=0 挂到对应表上，deleted=1 进归档列表
        db.list("SELECT * FROM sheet_logs ORDER BY time DESC, rowid DESC", rs -> {
            if (rs.getInt("deleted") == 1) {
                deleted.add(new ArchivedLog(rs.getString("sheet_id"), rs.getString("sheet_name"),
                        rs.getString("time"), rs.getString("action"), rs.getString("detail"),
                        rs.getString("level"), rs.getString("id")));
            } else {
                SheetRecord.LogEntry entry = new SheetRecord.LogEntry();
                entry.id = rs.getString("id");
                entry.time = rs.getString("time");
                entry.action = rs.getString("action");
                entry.detail = rs.getString("detail");
                entry.level = rs.getString("level");
                live.computeIfAbsent(rs.getString("sheet_id"), key -> new ArrayList<>()).add(entry);
            }
            return null;
        });
        for (SheetRecord record : items.values()) {
            record.logs = live.getOrDefault(record.id, new ArrayList<>());
        }
        archived.clear();
        archived.addAll(deleted);
    }

    // 已删除表格的日志（只读副本），和存活表格的日志合并后才对外的操作日志
    public synchronized List<ArchivedLog> archived() {
        return new ArrayList<>(archived);
    }

    // 表被删掉时调用：这张表原有的操作记录整体转存（deleted 置 1），再补一条「删除表格」。
    public synchronized void archiveSheet(String sheetId, String sheetName, List<SheetRecord.LogEntry> history,
                                          String time, String action, String detail, String level) {
        db.tx(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE sheet_logs SET deleted = 1 WHERE sheet_id = ? AND deleted = 0")) {
                ps.setString(1, sheetId);
                ps.executeUpdate();
            }
            insertLog(connection, sheetId, sheetName, time, action, detail, level, true, null);
            trimInTx(connection);
            return null;
        });
        reloadLogs();
    }

    // 删日志时同步清理已删除表格留下的那几条
    public synchronized void removeArchived(Set<String> ids, boolean clearAll) {
        db.tx(connection -> {
            if (clearAll) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM sheet_logs WHERE deleted = 1")) {
                    ps.executeUpdate();
                }
            } else if (ids != null && !ids.isEmpty()) {
                String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM sheet_logs WHERE deleted = 1 AND id IN (" + placeholders + ")")) {
                    int index = 1;
                    for (String id : ids) {
                        ps.setString(index++, id);
                    }
                    ps.executeUpdate();
                }
            }
            return null;
        });
        reloadLogs();
    }

    public synchronized List<SheetRecord> all() {
        List<SheetRecord> list = new ArrayList<>(items.values());
        list.sort(Comparator.comparing((SheetRecord r) -> r.updatedAt == null ? "" : r.updatedAt).reversed());
        return list;
    }

    public synchronized Optional<SheetRecord> find(String id) {
        return Optional.ofNullable(items.get(id));
    }

    public SheetRecord require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("没有找到表格 " + id));
    }

    public synchronized String nextId() {
        String prefix = "S" + LocalDate.now().format(DAY) + "-";
        int max = 0;
        for (String id : items.keySet()) {
            if (!id.startsWith(prefix)) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // 手工改过的 id 不参与编号
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    public synchronized void put(SheetRecord record) {
        items.put(record.id, record);
        db.tx(connection -> {
            upsertSheet(connection, record);
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sheet_logs WHERE sheet_id = ? AND deleted = 0")) {
                ps.setString(1, record.id);
                ps.executeUpdate();
            }
            for (SheetRecord.LogEntry entry : record.logs) {
                insertLog(connection, record.id, record.name, entry.time, entry.action,
                        entry.detail, entry.level, false, entry.id);
            }
            trimInTx(connection);
            return null;
        });
        reloadLogs();
    }

    // 日志全局只保留最近 MAX_LOGS 条：按时间倒序取前 100，其余从各表的尾部裁掉。
    private int trimInTx(Connection connection) throws SQLException {
        int before = countLogs(connection);
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sheet_logs WHERE id NOT IN ("
                        + "SELECT id FROM sheet_logs ORDER BY time DESC, rowid DESC LIMIT " + MAX_LOGS + ")")) {
            ps.executeUpdate();
        }
        return before - countLogs(connection);
    }

    private int countLogs(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM sheet_logs");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized void remove(String id) {
        items.remove(id);
        db.tx(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sheets WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            // 只删这张表还「活着」的日志，deleted=1 的是删表留痕，必须保留
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM sheet_logs WHERE sheet_id = ? AND deleted = 0")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        reloadLogs();
        try {
            Files.deleteIfExists(paths.sheetPath(id));
            Files.deleteIfExists(paths.sheetBackupPath(id));
        } catch (Exception e) {
            log.warn("删除表格文件失败：{}", e.getMessage());
        }
    }

    // 「删除所有表格数据」：整库清空，表记录、日志（连删表留痕一起）、磁盘上的 xlsx 和撤销快照全删掉，删完就回不去了
    public synchronized int clearAll() {
        int count = items.size();
        items.clear();
        archived.clear();
        db.tx(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sheets")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sheet_logs")) {
                ps.executeUpdate();
            }
            return null;
        });
        deleteSheetFiles(paths.sheets());
        deleteSheetFiles(paths.sheetBackup());
        return count;
    }

    // 只清目录里的 xlsx，目录本身保留；表格文件只放一层，不递归子目录。
    private void deleteSheetFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path target : files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .toList()) {
                try {
                    Files.deleteIfExists(target);
                } catch (Exception e) {
                    log.warn("删除表格文件失败：{}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("清空表格目录失败：{}", e.getMessage());
        }
    }

    public synchronized void save() {
        db.tx(connection -> {
            for (SheetRecord record : items.values()) {
                upsertSheet(connection, record);
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM sheet_logs WHERE deleted = 0")) {
                ps.executeUpdate();
            }
            for (SheetRecord record : items.values()) {
                for (SheetRecord.LogEntry entry : record.logs) {
                    insertLog(connection, record.id, record.name, entry.time, entry.action,
                            entry.detail, entry.level, false, entry.id);
                }
            }
            trimInTx(connection);
            return null;
        });
        reloadLogs();
    }

    private void upsertSheet(Connection connection, SheetRecord record) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sheets (id, name, origin, original_name, task_id, note, created_at, updated_at, "
                        + "columns, row_count, revision_count, last_summary, undo_available, undo_row_count, revisions) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET name = excluded.name, origin = excluded.origin, "
                        + "original_name = excluded.original_name, task_id = excluded.task_id, note = excluded.note, "
                        + "created_at = excluded.created_at, updated_at = excluded.updated_at, "
                        + "columns = excluded.columns, row_count = excluded.row_count, "
                        + "revision_count = excluded.revision_count, last_summary = excluded.last_summary, "
                        + "undo_available = excluded.undo_available, undo_row_count = excluded.undo_row_count, "
                        + "revisions = excluded.revisions")) {
            ps.setString(1, record.id);
            ps.setString(2, record.name);
            ps.setString(3, record.origin);
            ps.setString(4, record.originalName);
            ps.setString(5, record.taskId);
            ps.setString(6, record.note);
            ps.setString(7, record.createdAt);
            ps.setString(8, record.updatedAt);
            ps.setString(9, db.toJson(record.columns));
            ps.setInt(10, record.rowCount);
            ps.setInt(11, record.revisionCount);
            ps.setString(12, record.lastSummary);
            ps.setInt(13, record.undoAvailable ? 1 : 0);
            ps.setInt(14, record.undoRowCount);
            ps.setString(15, db.toJson(record.revisions));
            ps.executeUpdate();
        }
    }

    private void insertLog(Connection connection, String sheetId, String sheetName, String time, String action,
                           String detail, String level, boolean deleted, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO sheet_logs (id, sheet_id, sheet_name, time, action, detail, level, deleted) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, (id == null || id.isBlank()) ? SheetRecord.LogEntry.newId() : id);
            ps.setString(2, sheetId);
            ps.setString(3, sheetName);
            ps.setString(4, time);
            ps.setString(5, action);
            ps.setString(6, detail);
            ps.setString(7, level);
            ps.setInt(8, deleted ? 1 : 0);
            ps.executeUpdate();
        }
    }
}
