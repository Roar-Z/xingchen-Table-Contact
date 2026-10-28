package com.xingchen.huibiao.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能体的“经验记忆”。
 *
 * 每跑成功一单，就把这次“场景 + 模板 + 源表列 + 映射”记一条；下次遇到同类表，
 * 先用这些历史配置补齐当前映射的缺口，再交给规则/大模型兜底。
 * 没有向量库，用“模板名 + 场景 + 列名重合度”做检索，单机演示足够，也能解释清楚。
 */
@Service
public class ExperienceStore {

    private static final Logger log = LoggerFactory.getLogger(ExperienceStore.class);
    private static final int MAX_ITEMS = 30;
    private static final double HIT_SCORE = 0.7;

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private final List<Item> items = new ArrayList<>();

    public ExperienceStore(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        Path file = paths.agentExperienceFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Item> loaded = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<Item>>() {
                    });
            if (loaded != null) {
                items.clear();
                items.addAll(loaded);
            }
        } catch (Exception e) {
            log.warn("读取智能体经验失败，本次按空经验启动：{}", e.getMessage());
        }
    }

    public List<Item> all() {
        return new ArrayList<>(items);
    }

    /**
     * 找最像的一条历史经验：模板名一致最重要，其次是场景和源表列的重合度。
     */
    public Item bestMatch(String scene, String templateName, List<String> sourceColumns) {
        Item best = null;
        double bestScore = 0;
        for (Item item : items) {
            double score = 0;
            if (!TextUtils.isBlank(templateName) && templateName.equals(item.templateName)) {
                score += 0.5;
            }
            if (!TextUtils.isBlank(scene) && scene.equals(item.scene)) {
                score += 0.2;
            }
            score += 0.3 * overlap(sourceColumns, item.sourceColumns);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore >= HIT_SCORE ? best : null;
    }

    private double overlap(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        Set<String> a = new HashSet<>(left);
        Set<String> b = new HashSet<>(right);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        int same = 0;
        for (String name : a) {
            if (b.contains(name)) {
                same++;
            }
        }
        return union.isEmpty() ? 0 : same * 1d / union.size();
    }

    /**
     * 学一次经验。完全相同的配置只累加次数，不重复占位置。
     */
    public Item learn(String scene, String templateName, List<String> sourceColumns,
                      List<Entry> mapping, String note) {
        if (mapping == null || mapping.isEmpty()) {
            return null;
        }
        String signature = signatureOf(scene, templateName, mapping);
        for (Item item : items) {
            if (signature.equals(signatureOf(item.scene, item.templateName, item.mapping))) {
                item.wins += 1;
                item.savedAt = TimeUtils.nowSecond();
                if (!TextUtils.isBlank(note)) {
                    item.note = note;
                }
                write();
                return item;
            }
        }
        Item item = new Item();
        item.id = "exp-" + System.currentTimeMillis();
        item.scene = scene == null ? "" : scene;
        item.templateName = templateName == null ? "" : templateName;
        item.sourceColumns = sourceColumns == null ? new ArrayList<>() : new ArrayList<>(sourceColumns);
        item.mapping = new ArrayList<>(mapping);
        item.note = note == null ? "" : note;
        item.savedAt = TimeUtils.nowSecond();
        item.wins = 1;
        items.add(0, item);
        while (items.size() > MAX_ITEMS) {
            items.remove(items.size() - 1);
        }
        write();
        log.info("智能体记住一条新经验：{} / {}（{} 条映射）", item.scene, item.templateName, item.mapping.size());
        return item;
    }

    public boolean remove(String id) {
        boolean removed = items.removeIf(item -> item.id.equals(id));
        if (removed) {
            write();
        }
        return removed;
    }

    private String signatureOf(String scene, String templateName, List<Entry> mapping) {
        StringBuilder sb = new StringBuilder();
        sb.append(scene).append('|').append(templateName).append('|');
        if (mapping != null) {
            mapping.stream()
                    .map(entry -> entry.target + ">" + entry.fileName + "#" + entry.column + "#" + entry.expr)
                    .sorted()
                    .forEach(text -> sb.append(text).append(';'));
        }
        return sb.toString();
    }

    private void write() {
        Path file = paths.agentExperienceFile();
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(items),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("保存智能体经验失败：{}", e.getMessage());
        }
    }

    /** 一条经验里的一个映射，只记文件名和列名，换机器、换 fileId 也能复用。 */
    public static class Entry {
        public String target;
        public String fileName;
        public String column;
        public String expr;

        public Entry() {
        }

        public Entry(String target, String fileName, String column, String expr) {
            this.target = target;
            this.fileName = fileName;
            this.column = column;
            this.expr = expr;
        }
    }

    public static class Item {
        public String id;
        public String scene;
        public String templateName;
        public String note;
        public String savedAt;
        public int wins;
        public List<String> sourceColumns = new ArrayList<>();
        public List<Entry> mapping = new ArrayList<>();
    }

    /** 给前端看的时候不暴露内部结构，转成简单 Map。 */
    public List<Map<String, Object>> summary() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item item : items) {
            out.add(Map.of(
                    "id", item.id,
                    "scene", item.scene,
                    "template_name", item.templateName,
                    "wins", item.wins,
                    "saved_at", item.savedAt,
                    "note", item.note == null ? "" : item.note,
                    "mapping_count", item.mapping.size()));
        }
        return out;
    }
}
