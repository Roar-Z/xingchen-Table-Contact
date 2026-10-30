package com.xingchen.huibiao.service.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 智能体模式的「经验记忆」。
@Service
public class BridgeMemory {

    private static final Logger log = LoggerFactory.getLogger(BridgeMemory.class);
    private static final int MAX_ITEMS = 30;
    // 列名重合 0.5 + 表类型一致 0.25 + 业务目标一致 0.25，过线才算同类
    private static final double HIT_SCORE = 0.55;
    private static final String SCOPE = "bridge_memory";

    private final SqliteDb db;
    private final ObjectMapper mapper;
    private final List<Lesson> items = new ArrayList<>();

    public BridgeMemory(SqliteDb db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        items.clear();
        String body = db.kv(SCOPE);
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            List<Lesson> loaded = mapper.readValue(body, new TypeReference<List<Lesson>>() {
            });
            if (loaded != null) {
                items.addAll(loaded);
            }
        } catch (Exception e) {
            log.warn("读取桥接经验失败，本次按空经验启动：{}", e.getMessage());
        }
    }

    public List<Lesson> all() {
        return new ArrayList<>(items);
    }

    // 找最像的一条历史经验：列名重合度最要紧，其次是表类型和业务目标是否一致。
    public Lesson bestMatch(List<String> columns, String kinds, String scene) {
        Lesson best = null;
        double bestScore = 0;
        for (Lesson item : items) {
            if (offline(item)) continue;
            double similar = 0.5 * overlap(columns, item.columns);
            if (!TextUtils.isBlank(kinds) && kinds.equals(item.kinds)) {
                similar += 0.25;
            }
            if (!TextUtils.isBlank(scene) && scene.equals(item.scene)) {
                similar += 0.25;
            }
            if (similar < HIT_SCORE) continue;
            double score = similar * (0.5 + 0.5 * trust(item));
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    // 连续被否两次且一次都没被确认过，就停用这条经验
    public boolean offline(Lesson item) {
        return item.rejected >= 2 && item.confirmed == 0;
    }

    // 可信度 0~1：被人工确认加分，被否减分，异常占比高的那次减分。
    public double trust(Lesson item) {
        double good = 1 + item.confirmed + Math.min(item.wins, 5) * 0.5;
        double bad = 2 * item.rejected + (item.anomalyRate >= 0.5 ? 2 : 0);
        return good / (good + bad);
    }

    private double overlap(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> a = new HashSet<>();
        for (String item : left) {
            if (item != null) a.add(TextUtils.norm(item));
        }
        Set<String> b = new HashSet<>();
        for (String item : right) {
            if (item != null) b.add(TextUtils.norm(item));
        }
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) {
            return 0;
        }
        int same = 0;
        for (String item : a) {
            if (b.contains(item)) same++;
        }
        return same * 1d / union.size();
    }

    // 学一次经验。完全相同的配置只累加次数，不重复占位置；超上限就丢掉最旧的一条。
    public Lesson learn(String scene, String datasetName, String kinds, String joinKey, List<String> columns,
                        List<Alias> aligns, List<String> computes, List<String> outputColumns,
                        double anomalyRate, String goal, int attempts, boolean passed) {
        if (aligns == null || aligns.isEmpty()) {
            return null;
        }
        String signature = signatureOf(scene, kinds, aligns);
        for (Lesson item : items) {
            if (signature.equals(signatureOf(item.scene, item.kinds, item.aligns))) {
                item.wins += 1;
                item.savedAt = TimeUtils.nowSecond();
                item.datasetName = datasetName == null ? "" : datasetName;
                item.joinKey = joinKey;
                item.computes = computes == null ? new ArrayList<>() : new ArrayList<>(computes);
                item.outputColumns = outputColumns == null ? new ArrayList<>() : new ArrayList<>(outputColumns);
                item.anomalyRate = anomalyRate;
                item.goal = goal == null ? "" : goal;
                item.attempts = attempts;
                item.passed = passed;
                write();
                return item;
            }
        }
        Lesson item = new Lesson();
        item.id = "bexp-" + System.currentTimeMillis();
        item.scene = scene == null ? "" : scene;
        item.datasetName = datasetName == null ? "" : datasetName;
        item.kinds = kinds == null ? "" : kinds;
        item.joinKey = joinKey;
        item.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
        item.aligns = new ArrayList<>(aligns);
        item.computes = computes == null ? new ArrayList<>() : new ArrayList<>(computes);
        item.outputColumns = outputColumns == null ? new ArrayList<>() : new ArrayList<>(outputColumns);
        item.savedAt = TimeUtils.nowSecond();
        item.anomalyRate = anomalyRate;
        item.goal = goal == null ? "" : goal;
        item.attempts = attempts;
        item.passed = passed;
        item.wins = 1;
        items.add(0, item);
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
        write();
        log.info("桥接智能体记住一条新经验：{} / {}（{} 处对齐，{} 轮，异常占比 {}%）",
                item.scene, item.datasetName, item.aligns.size(), attempts, Math.round(anomalyRate * 100));
        return item;
    }

    // 同类型数据集上次“要做什么”：只取已达标的经验，避免把没做成的目标当成默认目标带下去。
    public String goalFor(String kinds) {
        if (TextUtils.isBlank(kinds)) return "";
        for (Lesson item : items) {
            if (offline(item) || !item.passed) continue;
            if (!kinds.equals(item.kinds)) continue;
            if (!TextUtils.isBlank(item.goal)) return item.goal;
        }
        return "";
    }

    // 人工反馈：对 / 不对。说“有用”加可信度，说“不适用”减可信度；
    public Lesson feedback(String id, boolean useful) {
        for (Lesson item : items) {
            if (!item.id.equals(id)) continue;
            if (useful) {
                item.confirmed += 1;
                item.rejected = 0;
            } else {
                item.rejected += 1;
            }
            write();
            log.info("经验 {} 反馈{}：确认 {} 次 / 否决 {} 次{}", item.id, useful ? "有用" : "不适用",
                    item.confirmed, item.rejected, offline(item) ? "，已停用" : "");
            return item;
        }
        return null;
    }

    public boolean remove(String id) {
        boolean removed = items.removeIf(item -> item.id.equals(id));
        if (removed) {
            write();
        }
        return removed;
    }

    // 「清理数据」时一起清掉：经验是跑单过程中沉淀的运行期数据，留着会显得没删干净。
    public int clear() {
        int count = items.size();
        items.clear();
        write();
        return count;
    }

    private String signatureOf(String scene, String kinds, List<Alias> aligns) {
        StringBuilder sb = new StringBuilder();
        sb.append(scene).append('|').append(kinds).append('|');
        if (aligns != null) {
            aligns.stream()
                    .filter(alias -> alias != null && alias.column != null && alias.field != null)
                    .map(alias -> TextUtils.norm(alias.kind) + ">" + TextUtils.norm(alias.column) + "=" + alias.field)
                    .sorted()
                    .forEach(text -> sb.append(text).append(';'));
        }
        return sb.toString();
    }

    private void write() {
        db.kvPut(SCOPE, items);
    }

    // 一条经验里的一处对齐：哪类表的哪一列 → 哪个标准字段
    public static class Alias {
        public String kind;
        public String column;
        public String field;

        public Alias() {
        }

        public Alias(String kind, String column, String field) {
            this.kind = kind;
            this.column = column;
            this.field = field;
        }
    }

    public static class Lesson {
        public String id;
        // 业务目标（指令原文归一化后），同一目标的月度任务会命中
        public String scene;
        public String datasetName;
        // 表类型组合，例如 hr_employee+erp_salary+oa_leave
        public String kinds;
        public String joinKey;
        public String savedAt;
        public int wins;
        // 人工确认“有用”的次数
        public int confirmed;
        // 人工判“不适用”的次数，连续两次且无确认就下线
        public int rejected;
        // 最近一次执行的异常行占比，越高越说明方案值得怀疑
        public double anomalyRate;
        // 这次跑的业务目标：下次同类数据集直接沿用，不用等人再写一遍
        public String goal;
        // 这次自主循环跑了几轮（1 表示一次过）
        public int attempts;
        // 这次是否达到验收标准
        public boolean passed;
        public List<String> columns = new ArrayList<>();
        public List<Alias> aligns = new ArrayList<>();
        // 形如「应发工资=基本工资+绩效-扣款」
        public List<String> computes = new ArrayList<>();
        public List<String> outputColumns = new ArrayList<>();
    }

    // 给前端看的时候不暴露内部结构，转成简单 Map。
    public List<Map<String, Object>> summary() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Lesson item : items) {
            // Map.of 最多 10 组键值，这里字段多，统一用 LinkedHashMap 保持顺序
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", item.id);
            row.put("scene", item.scene == null ? "" : item.scene);
            row.put("dataset_name", item.datasetName == null ? "" : item.datasetName);
            row.put("kinds", item.kinds == null ? "" : item.kinds);
            row.put("join_key", item.joinKey == null ? "" : item.joinKey);
            row.put("wins", item.wins);
            row.put("confirmed", item.confirmed);
            row.put("rejected", item.rejected);
            row.put("trust", Math.round(trust(item) * 100));
            row.put("offline", offline(item));
            row.put("anomaly_rate", Math.round(item.anomalyRate * 100));
            row.put("goal", item.goal == null ? "" : item.goal);
            row.put("attempts", item.attempts);
            row.put("passed", item.passed);
            row.put("saved_at", item.savedAt == null ? "" : item.savedAt);
            row.put("align_count", item.aligns == null ? 0 : item.aligns.size());
            row.put("compute_count", item.computes == null ? 0 : item.computes.size());
            out.add(row);
        }
        return out;
    }
}
