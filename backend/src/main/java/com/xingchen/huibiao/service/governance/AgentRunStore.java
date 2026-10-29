package com.xingchen.huibiao.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.AgentPlan;
import com.xingchen.huibiao.model.AgentStep;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体运行观测。
 *
 * 每次配置编排都记一条：用了模型还是规则、调了哪些工具、花了多久、耗了多少 token、
 * 是否兜底、映射几条。运行面板的“成功率 / 兜底率 / token / 工具频次”都从这里算。
 * 只是本地 JSON 文件，不依赖监控系统，单机演示和答辩都能直接看到数字。
 */
@Service
public class AgentRunStore {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStore.class);
    /** 本地只保留最近 100 条运行记录，超出后丢弃最旧的，避免长期运行把磁盘写满。 */
    static final int MAX_RUNS = 100;
    private static final int RECENT_LIMIT = 12;

    /** 分页每页上限，防止前端一次把 100 条全拉走。 */
    private static final int MAX_PAGE_SIZE = 50;

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private final List<Run> runs = new ArrayList<>();

    public AgentRunStore(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        Path file = paths.agentRunsFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Run> loaded = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<Run>>() {
                    });
            if (loaded != null) {
                runs.clear();
                runs.addAll(loaded);
            }
            // 旧版本可能存了 300 条，启动时按新上限收敛并回写
            if (runs.size() > MAX_RUNS) {
                runs.subList(0, runs.size() - MAX_RUNS).clear();
                save();
            }
        } catch (Exception e) {
            log.warn("读取智能体运行记录失败，本次按空记录启动：{}", e.getMessage());
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
        runs.add(run);
        while (runs.size() > MAX_RUNS) {
            runs.remove(0);
        }
        save();
    }

    public synchronized Map<String, Object> stats() {
        int total = runs.size();
        int ok = 0;
        int failed = 0;
        int llm = 0;
        int rules = 0;
        int fallback = 0;
        int experienceHits = 0;
        long durationSum = 0;
        long tokensIn = 0;
        long tokensOut = 0;
        int modelCalls = 0;
        int stepSum = 0;
        Map<String, Integer> toolFrequency = new LinkedHashMap<>();
        Map<String, Integer> scenes = new LinkedHashMap<>();

        for (Run run : runs) {
            if ("ok".equals(run.status)) {
                ok++;
            } else {
                failed++;
            }
            if ("llm".equals(run.mode)) {
                llm++;
            } else {
                rules++;
            }
            if (run.fallback) {
                fallback++;
            }
            if (run.experienceHit) {
                experienceHits++;
            }
            durationSum += Math.max(run.durationMs, 0);
            tokensIn += Math.max(run.tokensIn, 0);
            tokensOut += Math.max(run.tokensOut, 0);
            modelCalls += Math.max(run.modelCalls, 0);
            stepSum += run.steps;
            run.tools.forEach(tool -> toolFrequency.merge(tool, 1, Integer::sum));
            if (!isBlank(run.scene)) {
                scenes.merge(run.scene, 1, Integer::sum);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("ok", ok);
        out.put("failed", failed);
        out.put("success_rate", total == 0 ? 0 : Math.round(ok * 100.0 / total));
        out.put("llm_runs", llm);
        out.put("rules_runs", rules);
        out.put("fallback_runs", fallback);
        out.put("fallback_rate", total == 0 ? 0 : Math.round(fallback * 100.0 / total));
        out.put("avg_duration_ms", total == 0 ? 0 : Math.round(durationSum * 1.0 / total));
        out.put("tokens_in", tokensIn);
        out.put("tokens_out", tokensOut);
        out.put("avg_tokens", total == 0 ? 0 : Math.round((tokensIn + tokensOut) * 1.0 / total));
        out.put("model_calls", modelCalls);
        out.put("avg_steps", total == 0 ? 0 : Math.round(stepSum * 1.0 / total));
        out.put("experience_hits", experienceHits);
        out.put("tool_frequency", toolFrequency);
        out.put("scenes", scenes);
        out.put("last_at", total == 0 ? "" : runs.get(total - 1).at);
        out.put("recent", recent(RECENT_LIMIT));
        return out;
    }

    public synchronized List<Run> recent(int limit) {
        int size = Math.min(Math.max(limit, 1), runs.size());
        List<Run> tail = new ArrayList<>(runs.subList(runs.size() - size, runs.size()));
        tail.sort(Comparator.comparing((Run run) -> run.at).reversed());
        return tail;
    }

    /**
     * 运行记录分页：新的在前，返回 items / total / page / pages。
     * 前端只拿当前页，统计口径仍按全部留存记录算。
     */
    public synchronized Map<String, Object> page(int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int total = runs.size();
        int pages = total == 0 ? 1 : (int) Math.ceil(total * 1.0 / size);
        int current = Math.min(Math.max(page, 1), pages);

        List<Run> ordered = new ArrayList<>(runs);
        ordered.sort(Comparator.comparing((Run run) -> run.at).reversed());
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", new ArrayList<>(ordered.subList(from, to)));
        out.put("total", total);
        out.put("page", current);
        out.put("page_size", size);
        out.put("pages", pages);
        // 单页最多装得下 MAX_RUNS 条，这里顺带告诉前端本地留存上限
        out.put("keep", MAX_RUNS);
        return out;
    }

    private void save() {
        Path file = paths.agentRunsFile();
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(runs),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("保存智能体运行记录失败：{}", e.getMessage());
        }
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

    /** 一次配置编排的运行记录。 */
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
