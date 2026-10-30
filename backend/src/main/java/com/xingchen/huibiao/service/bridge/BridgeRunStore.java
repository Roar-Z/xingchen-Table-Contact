package com.xingchen.huibiao.service.bridge;

import com.xingchen.huibiao.config.SqliteDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 运行现场的落库实现：一单一个 kv 记录（scope = bridge_run:&lt;id&gt;），内容和前端轮询返回的一致。
@Service
public class BridgeRunStore implements BridgeRunArchive {

    private static final Logger log = LoggerFactory.getLogger(BridgeRunStore.class);

    private static final String SCOPE_PREFIX = "bridge_run:";

    // 最多留多少单的运行现场：这是观察数据，够回看最近这些单就行，不能让 kv 无限膨胀
    private static final int KEEP_RUNS = 100;

    private final SqliteDb db;

    public BridgeRunStore(SqliteDb db) {
        this.db = db;
    }

    @Override
    public void save(String id, List<Map<String, Object>> events, Map<String, Object> meta) {
        if (id == null || id.isBlank()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", events == null ? List.of() : events);
        body.put("meta", meta == null ? Map.of() : meta);
        try {
            db.kvPut(SCOPE_PREFIX + id, body);
            trim();
        } catch (Exception e) {
            log.warn("运行现场落库失败（不影响这一单的执行）：{}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> events(String id) {
        Map<String, Object> body = body(id);
        if (body.isEmpty()) return List.of();
        Object events = body.get("events");
        if (!(events instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) out.add(castMap(map));
        }
        return out;
    }

    @Override
    public Map<String, Object> meta(String id) {
        Map<String, Object> body = body(id);
        if (body.isEmpty()) return Map.of();
        Object meta = body.get("meta");
        return meta instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    @Override
    public void clear(String id) {
        if (id == null || id.isBlank()) return;
        try {
            db.update("DELETE FROM kv WHERE scope = ?", SCOPE_PREFIX + id);
        } catch (Exception e) {
            log.warn("清理旧运行现场失败：{}", e.getMessage());
        }
    }

    private Map<String, Object> body(String id) {
        if (id == null || id.isBlank()) return Map.of();
        try {
            String raw = db.kv(SCOPE_PREFIX + id);
            return raw == null || raw.isBlank() ? Map.of() : db.jsonMap(raw);
        } catch (Exception e) {
            log.warn("读取运行现场失败：{}", e.getMessage());
            return Map.of();
        }
    }

    // 只留最近 KEEP_RUNS 单：按 updated_at 从旧到新删，正在跑的那一单刚写过，不会被误删
    private void trim() {
        long total = db.count("SELECT COUNT(*) FROM kv WHERE scope LIKE ?", SCOPE_PREFIX + "%");
        if (total <= KEEP_RUNS) return;
        db.update("DELETE FROM kv WHERE scope IN (SELECT scope FROM kv WHERE scope LIKE ? "
                + "ORDER BY updated_at ASC LIMIT ?)", SCOPE_PREFIX + "%", total - KEEP_RUNS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
