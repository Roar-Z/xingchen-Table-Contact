package com.xingchen.huibiao.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.model.AgentPlan;
import com.xingchen.huibiao.model.AgentStep;
import com.xingchen.huibiao.util.TimeUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 智能体运行观测，落 SQLite 的 agent_runs 表。
@Service
public class AgentRunStore {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStore.class);
    // 本地只保留最近 100 条运行记录，超出后丢弃最旧的，避免长期运行把磁盘写满。
    static final int MAX_RUNS = 100;
    private static final int RECENT_LIMIT = 12;

    // 分页每页上限，防止前端一次把 100 条全拉走。
    private static final int MAX_PAGE_SIZE = 50;

    private final DataPaths paths;
    private final SqliteDb db;
    private final ObjectMapper mapper;

    public AgentRunStore(DataPaths paths, SqliteDb db, ObjectMapper mapper) {
        this.paths = paths;
        this.db = db;
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        migrateLegacy();
        // 旧版本可能存了 300 条，启动时按新上限收敛
        db.update("DELETE FROM agent_runs WHERE id NOT IN ("
                + "SELECT id FROM agent_runs ORDER BY id DESC LIMIT " + MAX_RUNS + ")");
    }

    private void migrateLegacy() {
        Path file = paths.agentRunsFile();
        if (!db.isEmpty("agent_runs") || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Run> loaded = mapper.readValue(Files.readString(file), new TypeReference<List<Run>>() {
            });
            if (loaded == null || loaded.isEmpty()) {
                db.retireLegacy(file);
                return;
            }
            db.tx(connection -> {
                for (Run run : loaded) {
                    insert(connection, run);
                }
                return null;
            });
            db.update("DELETE FROM agent_runs WHERE id NOT IN ("
                    + "SELECT id FROM agent_runs ORDER BY id DESC LIMIT " + MAX_RUNS + ")");
            log.info("已把 {} 条旧运行记录导入数据库", loaded.size());
            db.retireLegacy(file);
        } catch (Exception e) {
            log.warn("旧运行记录导入失败，本次按空记录启动：{}", e.getMessage());
        }
    }

    public synchronized Run record(AgentPlan plan, int fileCount, long durationMs) {
        Run run = new Run();
        run.at = TimeUtils.nowSecond();
        run.status = "ok";
        run.mode = plan.mode;
        run.fallback = plan.fallback;
        run.files = fileCount;
        run.steps = plan.steps == null ? 0 : plan.steps.size();
        run.fixes = plan.fixes == null ? 0 : plan.fixes.size();
        run.tokensIn = plan.tokensIn;
        run.tokensOut = plan.tokensOut;
        run.modelCalls = plan.modelCalls;
        run.durationMs = durationMs > 0 ? durationMs : plan.durationMs;
        run.experienceHit = !isBlank(plan.experienceId);
        if (plan.intent != null) {
            run.scene = plan.intent.scene;
            run.sceneName = plan.intent.sceneName;
            run.template = plan.intent.targetTemplate == null ? "" : plan.intent.targetTemplate.name();
            run.mappings = plan.intent.mapping == null ? 0 : plan.intent.mapping.size();
        }
        if (plan.steps != null) {
            for (AgentStep step : plan.steps) {
                if (!isBlank(step.tool) && !"skip".equals(step.status) && !run.tools.contains(step.tool)) {
                    run.tools.add(step.tool);
                }
            }
        }
        append(run);
        return run;
    }

    public synchronized Run recordFailure(String note, int fileCount, long durationMs) {
        Run run = new Run();
        run.at = TimeUtils.nowSecond();
        run.status = "failed";
        run.mode = "rules";
        run.files = fileCount;
        run.durationMs = durationMs;
        run.note = clip(note);
        append(run);
        return run;
    }

    private void append(Run run) {
        db.tx(connection -> {
            insert(connection, run);
            return null;
        });
        db.update("DELETE FROM agent_runs WHERE id NOT IN ("
                + "SELECT id FROM agent_runs ORDER BY id DESC LIMIT " + MAX_RUNS + ")");
    }

    private void insert(java.sql.Connection connection, Run run) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT INTO agent_runs (at, status, mode, fallback, files, scene, scene_name, template, mappings, "
                        + "steps, fixes, tokens_in, tokens_out, model_calls, duration_ms, experience_hit, note, tools) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, run.at);
            ps.setString(2, run.status);
            ps.setString(3, run.mode);
            ps.setInt(4, run.fallback ? 1 : 0);
            ps.setInt(5, run.files);
            ps.setString(6, run.scene);
            ps.setString(7, run.sceneName);
            ps.setString(8, run.template);
            ps.setInt(9, run.mappings);
            ps.setInt(10, run.steps);
            ps.setInt(11, run.fixes);
            ps.setInt(12, run.tokensIn);
            ps.setInt(13, run.tokensOut);
            ps.setInt(14, run.modelCalls);
            ps.setLong(15, run.durationMs);
            ps.setInt(16, run.experienceHit ? 1 : 0);
            ps.setString(17, run.note);
            ps.setString(18, db.toJson(run.tools));
            ps.executeUpdate();
        }
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> sums = db.one(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN status = 'ok' THEN 1 ELSE 0 END), 0) AS ok, "
                        + "COALESCE(SUM(CASE WHEN mode = 'llm' THEN 1 ELSE 0 END), 0) AS llm, "
                        + "COALESCE(SUM(fallback), 0) AS fallback, "
                        + "COALESCE(SUM(experience_hit), 0) AS exp_hit, "
                        + "COALESCE(SUM(duration_ms), 0) AS duration, "
                        + "COALESCE(SUM(tokens_in), 0) AS tokens_in, "
                        + "COALESCE(SUM(tokens_out), 0) AS tokens_out, "
                        + "COALESCE(SUM(model_calls), 0) AS model_calls, "
                        + "COALESCE(SUM(steps), 0) AS steps "
                        + "FROM agent_runs",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("total", rs.getInt("total"));
                    row.put("ok", rs.getInt("ok"));
                    row.put("llm", rs.getInt("llm"));
                    row.put("fallback", rs.getInt("fallback"));
                    row.put("exp_hit", rs.getInt("exp_hit"));
                    row.put("duration", rs.getLong("duration"));
                    row.put("tokens_in", rs.getLong("tokens_in"));
                    row.put("tokens_out", rs.getLong("tokens_out"));
                    row.put("model_calls", rs.getInt("model_calls"));
                    row.put("steps", rs.getInt("steps"));
                    return row;
                });
        if (sums == null) {
            sums = Map.of();
        }
        int total = asInt(sums.get("total"));
        int ok = asInt(sums.get("ok"));
        int llm = asInt(sums.get("llm"));
        int fallback = asInt(sums.get("fallback"));
        int expHit = asInt(sums.get("exp_hit"));
        long duration = asLong(sums.get("duration"));
        long tokensIn = asLong(sums.get("tokens_in"));
        long tokensOut = asLong(sums.get("tokens_out"));

        // 工具频次是数组，放进 Java 里累加；其余统计都已经在 SQL 里算完
        Map<String, Integer> toolFrequency = new LinkedHashMap<>();
        for (String tools : db.list("SELECT tools FROM agent_runs", rs -> rs.getString(1))) {
            for (String tool : db.jsonList(tools, String.class)) {
                toolFrequency.merge(tool, 1, Integer::sum);
            }
        }
        Map<String, Integer> scenes = new LinkedHashMap<>();
        db.list("SELECT scene, COUNT(*) AS n FROM agent_runs WHERE scene IS NOT NULL AND scene <> '' "
                + "GROUP BY scene ORDER BY n DESC", rs -> {
            scenes.put(rs.getString("scene"), rs.getInt("n"));
            return null;
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("ok", ok);
        out.put("failed", total - ok);
        out.put("success_rate", total == 0 ? 0 : Math.round(ok * 100.0 / total));
        out.put("llm_runs", llm);
        out.put("rules_runs", total - llm);
        out.put("fallback_runs", fallback);
        out.put("fallback_rate", total == 0 ? 0 : Math.round(fallback * 100.0 / total));
        out.put("avg_duration_ms", total == 0 ? 0 : Math.round(duration * 1.0 / total));
        out.put("tokens_in", tokensIn);
        out.put("tokens_out", tokensOut);
        out.put("avg_tokens", total == 0 ? 0 : Math.round((tokensIn + tokensOut) * 1.0 / total));
        out.put("model_calls", asInt(sums.get("model_calls")));
        out.put("avg_steps", total == 0 ? 0 : Math.round(asInt(sums.get("steps")) * 1.0 / total));
        out.put("experience_hits", expHit);
        out.put("tool_frequency", toolFrequency);
        out.put("scenes", scenes);
        String lastAt = db.one("SELECT at FROM agent_runs ORDER BY id DESC LIMIT 1", rs -> rs.getString(1));
        out.put("last_at", lastAt == null ? "" : lastAt);
        out.put("recent", recent(RECENT_LIMIT));
        return out;
    }

    // 运行观测里的「清理数据」：运行记录清空后统计自然归零，
    public synchronized int clear() {
        return db.tx(connection -> {
            int count;
            try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM agent_runs");
                 ResultSet rs = ps.executeQuery()) {
                count = rs.next() ? rs.getInt(1) : 0;
            }
            try (var ps = connection.prepareStatement("DELETE FROM agent_runs")) {
                ps.executeUpdate();
            }
            return count;
        });
    }

    public synchronized List<Run> recent(int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_RUNS);
        return db.list("SELECT * FROM agent_runs ORDER BY at DESC, id DESC LIMIT ?", this::mapRun, size);
    }

    // 运行记录分页：新的在前，返回 items / total / page / pages。
    public synchronized Map<String, Object> page(int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int total = (int) db.count("SELECT COUNT(*) FROM agent_runs");
        int pages = total == 0 ? 1 : (int) Math.ceil(total * 1.0 / size);
        int current = Math.min(Math.max(page, 1), pages);
        int offset = (current - 1) * size;
        List<Run> items = db.list("SELECT * FROM agent_runs ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
                this::mapRun, size, offset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", current);
        out.put("page_size", size);
        out.put("pages", pages);
        // 单页最多装得下 MAX_RUNS 条，这里顺带告诉前端本地留存上限
        out.put("keep", MAX_RUNS);
        return out;
    }

    private Run mapRun(ResultSet rs) throws SQLException {
        Run run = new Run();
        run.at = rs.getString("at");
        run.status = rs.getString("status");
        run.mode = rs.getString("mode");
        run.fallback = rs.getInt("fallback") == 1;
        run.files = rs.getInt("files");
        run.scene = rs.getString("scene");
        run.sceneName = rs.getString("scene_name");
        run.template = rs.getString("template");
        run.mappings = rs.getInt("mappings");
        run.steps = rs.getInt("steps");
        run.fixes = rs.getInt("fixes");
        run.tokensIn = rs.getInt("tokens_in");
        run.tokensOut = rs.getInt("tokens_out");
        run.modelCalls = rs.getInt("model_calls");
        run.durationMs = rs.getLong("duration_ms");
        run.experienceHit = rs.getInt("experience_hit") == 1;
        run.note = rs.getString("note");
        run.tools = db.jsonList(rs.getString("tools"), String.class);
        return run;
    }

    private int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    // 一次配置编排的运行记录。
    public static class Run {
        public String at;
        public String status = "ok";
        public String mode = "rules";
        public boolean fallback;
        public int files;
        public String scene = "";
        public String sceneName = "";
        public String template = "";
        public int mappings;
        public int steps;
        public int fixes;
        public int tokensIn;
        public int tokensOut;
        public int modelCalls;
        public long durationMs;
        public boolean experienceHit;
        public String note = "";
        public List<String> tools = new ArrayList<>();
    }
}
