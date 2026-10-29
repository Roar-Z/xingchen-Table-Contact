package com.xingchen.huibiao.service.sheet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.SheetRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 表格仓库：元数据存 data/sheets.json，数据本体存 data/sheets/{id}.xlsx。
 * 没有数据库，拷贝整个 data 目录就能把仓库带到另一台机器。
 */
@Service
public class SheetStore {

    private static final Logger log = LoggerFactory.getLogger(SheetStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 仓库所有表格的日志一共只留最近 100 条，跑得越多也不会把索引撑大 */
    public static final int MAX_LOGS = 100;

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private final Map<String, SheetRecord> items = new LinkedHashMap<>();
    /** 已删除表格留下的日志：表没了，但「删除表格」这条要留在操作日志里 */
    private final List<ArchivedLog> archived = new ArrayList<>();

    /** 表被删掉之后仍然要出现在操作日志里的一条记录 */
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

    public SheetStore(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        loadArchived();
        Path file = paths.sheetsFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<SheetRecord> list = mapper.readValue(file.toFile(), new TypeReference<List<SheetRecord>>() {
            });
            for (SheetRecord record : list) {
                if (record.id != null) {
                    // 老版本的日志没有 id，这里补上，前端才能按条勾选删除
                    for (SheetRecord.LogEntry entry : record.logs) {
                        if (entry.id == null || entry.id.isBlank()) {
                            entry.id = SheetRecord.LogEntry.newId();
                        }
                    }
                    items.put(record.id, record);
                }
            }
            log.info("已加载 {} 张表格", items.size());
        } catch (IOException e) {
            log.warn("表格索引读取失败，先按空仓库处理：{}", e.getMessage());
        }
    }

    private void loadArchived() {
        Path file = paths.sheetLogsFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<ArchivedLog> list = mapper.readValue(file.toFile(), new TypeReference<List<ArchivedLog>>() {
            });
            archived.clear();
            for (ArchivedLog entry : list) {
                if (entry.id == null || entry.id.isBlank()) {
                    entry.id = SheetRecord.LogEntry.newId();
                }
                archived.add(entry);
            }
        } catch (IOException e) {
            log.warn("已删除表格的日志读取失败，先忽略：{}", e.getMessage());
        }
    }

    /** 已删除表格的日志（只读副本），和存活表格的日志合并后才对外的操作日志 */
    public synchronized List<ArchivedLog> archived() {
        return new ArrayList<>(archived);
    }

    /**
     * 表被删掉时调用：这张表原有的操作记录整体转存，再补一条「删除表格」。
     * 表没了，但日志里的历史不能跟着消失。
     */
    public synchronized void archiveSheet(String sheetId, String sheetName, List<SheetRecord.LogEntry> history,
                                          String time, String action, String detail, String level) {
        if (history != null) {
            for (SheetRecord.LogEntry entry : history) {
                archived.add(new ArchivedLog(sheetId, sheetName, entry.time, entry.action,
                        entry.detail, entry.level, entry.id));
            }
        }
        archived.add(0, new ArchivedLog(sheetId, sheetName, time, action, detail, level, null));
        trimLogs();
        saveArchived();
    }

    /** 删日志时同步清理已删除表格留下的那几条 */
    public synchronized void removeArchived(Set<String> ids, boolean clearAll) {
        if (clearAll) {
            archived.clear();
        } else {
            archived.removeIf(entry -> ids.contains(entry.id));
        }
        saveArchived();
    }

    private void saveArchived() {
        try {
            Files.createDirectories(paths.root());
            Path tmp = paths.root().resolve("sheet-logs.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(archived));
            Files.move(tmp, paths.sheetLogsFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("已删除表格的日志保存失败：{}", e.getMessage());
        }
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
        trimLogs();
        save();
    }

    /**
     * 日志全局只保留最近 MAX_LOGS 条：按时间倒序取前 100，其余从各表的尾部裁掉。
     * 已删除表格留下的归档日志也一起参与，不然删表越多、日志越多。
     * 每次落盘前都跑一遍，不用等用户手动清理，仓库也不会越用越大。
     */
    public synchronized int trimLogs() {
        List<SheetRecord.LogEntry> live = new ArrayList<>();
        for (SheetRecord record : items.values()) {
            live.addAll(record.logs);
        }
        int total = live.size() + archived.size();
        if (total <= MAX_LOGS) {
            return 0;
        }

        // 两类日志混在一起按时间排，确定「最近 100 条」是哪 100 条
        record TimeKey(String time, boolean live, SheetRecord.LogEntry entry, ArchivedLog archivedEntry) {
        }
        List<TimeKey> keys = new ArrayList<>(total);
        for (SheetRecord.LogEntry entry : live) {
            keys.add(new TimeKey(String.valueOf(entry.time), true, entry, null));
        }
        for (ArchivedLog entry : archived) {
            keys.add(new TimeKey(String.valueOf(entry.time), false, null, entry));
        }
        keys.sort((a, b) -> b.time().compareTo(a.time()));

        Set<SheetRecord.LogEntry> keepLive = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ArchivedLog> keepArchived = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TimeKey key : keys.subList(0, MAX_LOGS)) {
            if (key.live()) {
                keepLive.add(key.entry());
            } else {
                keepArchived.add(key.archivedEntry());
            }
        }

        int dropped = 0;
        for (SheetRecord record : items.values()) {
            List<SheetRecord.LogEntry> kept = new ArrayList<>(record.logs.size());
            for (SheetRecord.LogEntry entry : record.logs) {
                if (keepLive.contains(entry)) {
                    kept.add(entry);
                } else {
                    dropped++;
                }
            }
            record.logs = kept;
        }
        int beforeArchived = archived.size();
        archived.removeIf(entry -> !keepArchived.contains(entry));
        dropped += beforeArchived - archived.size();
        return dropped;
    }

    public synchronized void remove(String id) {
        items.remove(id);
        save();
        try {
            Files.deleteIfExists(paths.sheetPath(id));
            Files.deleteIfExists(paths.sheetBackupPath(id));
        } catch (IOException e) {
            log.warn("删除表格文件失败：{}", e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(paths.root());
            Path tmp = paths.root().resolve("sheets.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(items.values()));
            Files.move(tmp, paths.sheetsFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存表格索引失败：" + e.getMessage(), e);
        }
    }
}
