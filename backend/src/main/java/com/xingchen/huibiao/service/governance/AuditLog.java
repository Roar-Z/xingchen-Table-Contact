package com.xingchen.huibiao.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.util.TimeUtils;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 审计日志：只追加、不改写，落 SQLite 的 audit_logs 表。
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    // 只保留最近 100 条审计记录，超出后丢最旧的；单页最多取 50 条。
    static final int MAX_ENTRIES = 100;
    private static final int MAX_PAGE_SIZE = 50;

    private final DataPaths paths;
    private final SqliteDb db;
    private final ObjectMapper mapper;

    public AuditLog(DataPaths paths, SqliteDb db, ObjectMapper mapper) {
        this.paths = paths;
        this.db = db;
        this.mapper = mapper;
    }

    // 启动时收敛：老版本 jsonl 日志先一次性导入，再把库里超长部分裁到上限。
    @PostConstruct
    public void init() {
        migrateLegacy();
        db.update("DELETE FROM audit_logs WHERE id NOT IN ("
                + "SELECT id FROM audit_logs ORDER BY id DESC LIMIT " + MAX_ENTRIES + ")");
    }

    // 老版本把审计日志写成 audit.log.jsonl，一行一条。只在表还空着时导入一次，
    private void migrateLegacy() {
        Path file = paths.auditLogFile();
        if (!db.isEmpty("audit_logs") || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(mapper.readValue(line, new TypeReference<Map<String, Object>>() {
                    }));
                } catch (Exception ignored) {
                    // 单行损坏跳过，不影响其余历史记录
                }
            }
            db.tx(connection -> {
                for (Map<String, Object> entry : entries) {
                    insert(connection, entry);
                }
                return null;
            });
            db.update("DELETE FROM audit_logs WHERE id NOT IN ("
                    + "SELECT id FROM audit_logs ORDER BY id DESC LIMIT " + MAX_ENTRIES + ")");
            log.info("已把 {} 条旧审计日志导入数据库", entries.size());
            db.retireLegacy(file);
        } catch (Exception e) {
            log.warn("旧审计日志导入失败，本次按空日志启动：{}", e.getMessage());
        }
    }

    public void record(String action, String target, String detail) {
        record(action, target, detail, Map.of());
    }

    public void record(String action, String target, String detail, Map<String, Object> extra) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", TimeUtils.nowSecond());
        entry.put("action", action == null ? "" : action);
        entry.put("target", clip(target, 120));
        entry.put("detail", clip(detail, 400));
        entry.put("actor", actor());
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (key != null && value != null) {
                    entry.put(key, value);
                }
            });
        }
        append(entry);
    }

    // 最近若干条，新的在前；只读需要的条数，不再整份读进内存。
    public List<Map<String, Object>> recent(int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_ENTRIES);
        return db.list("SELECT * FROM audit_logs ORDER BY id DESC LIMIT ?", this::toMap, size);
    }

    // 审计日志分页：新的在前，返回 items / total / page / pages。
    public Map<String, Object> page(int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int total = (int) db.count("SELECT COUNT(*) FROM audit_logs");
        int pages = total == 0 ? 1 : (int) Math.ceil(total * 1.0 / size);
        int current = Math.min(Math.max(page, 1), pages);
        int offset = (current - 1) * size;
        List<Map<String, Object>> items = db.list(
                "SELECT * FROM audit_logs ORDER BY id DESC LIMIT ? OFFSET ?", this::toMap, size, offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", current);
        out.put("page_size", size);
        out.put("pages", pages);
        out.put("keep", MAX_ENTRIES);
        return out;
    }

    // 运行观测里的「清理日志」：清空审计日志表，返回清掉的条数。
    public synchronized int clear() {
        return db.tx(connection -> {
            int count;
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM audit_logs");
                 ResultSet rs = ps.executeQuery()) {
                count = rs.next() ? rs.getInt(1) : 0;
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM audit_logs")) {
                ps.executeUpdate();
            }
            return count;
        });
    }

    private void append(Map<String, Object> entry) {
        try {
            db.tx(connection -> {
                insert(connection, entry);
                // 每次追加后把表收敛到最近 MAX_ENTRIES 条，长期运行也不会无限增长
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM audit_logs WHERE id NOT IN ("
                                + "SELECT id FROM audit_logs ORDER BY id DESC LIMIT " + MAX_ENTRIES + ")")) {
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("写审计日志失败：{}", e.getMessage());
        }
    }

    private void insert(Connection connection, Map<String, Object> entry) throws SQLException {
        Map<String, Object> extra = new LinkedHashMap<>(entry);
        String at = text(extra.remove("at"));
        String action = text(extra.remove("action"));
        String target = text(extra.remove("target"));
        String detail = text(extra.remove("detail"));
        String actor = text(extra.remove("actor"));
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit_logs (at, action, target, detail, actor, extra) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, at);
            ps.setString(2, action);
            ps.setString(3, target);
            ps.setString(4, detail);
            ps.setString(5, actor);
            ps.setString(6, extra.isEmpty() ? "{}" : db.toJson(extra));
            ps.executeUpdate();
        }
    }

    // 行 → 前端看到的结构：固定字段在前，附加字段展开在后面，和旧版本 jsonl 的格式一致。
    private Map<String, Object> toMap(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", rs.getString("at"));
        row.put("action", rs.getString("action"));
        row.put("target", rs.getString("target"));
        row.put("detail", rs.getString("detail"));
        row.put("actor", rs.getString("actor"));
        row.putAll(db.jsonMap(rs.getString("extra")));
        return row;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String actor() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "本机";
        }
        HttpServletRequest request = attrs.getRequest();
        if (request == null) {
            return "本机";
        }
        String header = request.getHeader("X-Operator");
        if (header != null && !header.isBlank()) {
            return clip(header, 60);
        }
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "本机" : clip(address, 60);
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
