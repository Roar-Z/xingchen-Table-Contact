package com.xingchen.huibiao.web;

import com.xingchen.huibiao.config.AppProperties;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.service.agent.AgentToolCatalog;
import com.xingchen.huibiao.service.agent.ExperienceStore;
import com.xingchen.huibiao.service.bridge.BridgeMemory;
import com.xingchen.huibiao.service.bridge.BridgeStore;
import com.xingchen.huibiao.service.governance.AgentRunStore;
import com.xingchen.huibiao.service.governance.AuditLog;
import com.xingchen.huibiao.service.sheet.SheetService;
import com.xingchen.huibiao.service.TaskService;
import com.xingchen.huibiao.util.TimeUtils;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// 运行观测：不引入 Langfuse/OTel 这类外部组件，单机也能回答四个问题——
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpsController.class);

    // 清理类接口必须带上对应的一句话，前端弹窗也已经要求用户手输一遍。
    private static final String CONFIRM_RUNS = "确认清理数据";
    private static final String CONFIRM_AUDIT = "确认清理日志";
    private static final String CONFIRM_SHEETS = "确认删除表格";

    private final AgentRunStore runs;
    private final AgentToolCatalog tools;
    private final AuditLog audit;
    private final ExperienceStore experience;
    private final AppProperties properties;
    private final ObjectProvider<ChatModel> chatModel;
    private final SheetService sheets;
    private final TaskService tasks;
    private final BridgeMemory bridgeMemory;
    private final BridgeStore bridgeStore;
    private final DataPaths paths;

    public OpsController(AgentRunStore runs, AgentToolCatalog tools, AuditLog audit,
                         ExperienceStore experience, AppProperties properties,
                         ObjectProvider<ChatModel> chatModel, SheetService sheets, TaskService tasks,
                         BridgeMemory bridgeMemory, BridgeStore bridgeStore, DataPaths paths) {
        this.runs = runs;
        this.tools = tools;
        this.audit = audit;
        this.experience = experience;
        this.properties = properties;
        this.chatModel = chatModel;
        this.sheets = sheets;
        this.tasks = tasks;
        this.bridgeMemory = bridgeMemory;
        this.bridgeStore = bridgeStore;
        this.paths = paths;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return runs.stats();
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return tools.list();
    }

    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return runs.page(page, pageSize);
    }

    // 审计日志分页：本地最多留 100 条，默认每页 20 条。
    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return audit.page(page, pageSize);
    }

    // 清理运行观测里的全部内容（「清理数据」）：
    @PostMapping("/runs/clear")
    public Map<String, Object> clearRuns(@RequestBody(required = false) Map<String, Object> body) {
        requireConfirm(body, CONFIRM_RUNS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", runs.clear());
        out.put("experiences_cleared", experience.clear());
        // 智能体模式的经验记忆同属运行期沉淀，一起清掉，不留“删了还在”的尾巴
        out.put("bridge_experiences_cleared", bridgeMemory.clear());
        out.put("audit_cleared", audit.clear());
        return out;
    }

    // 清理审计日志（「清理日志」）：日志本身就是被清对象，清完不再补记一条。
    @PostMapping("/audit/clear")
    public Map<String, Object> clearAudit(@RequestBody(required = false) Map<String, Object> body) {
        requireConfirm(body, CONFIRM_AUDIT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", audit.clear());
        return out;
    }

    // 删除所有表格数据（「删除所有表格」）：
    @PostMapping("/sheets/clear")
    public Map<String, Object> clearSheets(@RequestBody(required = false) Map<String, Object> body) {
        requireConfirm(body, CONFIRM_SHEETS);
        // 先清任务：有任务正在跑时这里会直接抛错，表格不会被删一半
        int tasksCleared = tasks.clearAll();
        Map<String, Object> out = sheets.clearAll();
        out.put("tasks_cleared", tasksCleared);
        // 智能体模式的数据集：源表、方案与产出都在 data/bridge/datasets/<id> 下，整目录删
        out.put("bridge_cleared", bridgeStore.delete(List.of()));
        // 两处副本：上传时的文件副本、跑完留在默认导出目录的副本；自定义导出目录不碰
        out.put("uploads_cleared", clearCopies(paths.uploads()));
        out.put("exports_cleared", clearCopies(paths.downloadsRoot()));
        audit.record("ops.sheets.clear", "表格仓库",
                "删除所有表格数据：表格文件、任务记录、智能体数据集与上传/导出副本一并清空");
        return out;
    }

    // 清空一个副本目录里的内容，目录本身保留，方便下次继续写入。
    private int clearCopies(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                try (Stream<Path> walk = Files.walk(child)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    // 单个文件删不掉不影响其它文件，记一笔日志继续
                    log.warn("删除副本失败 {}：{}", child, e.getMessage());
                }
                deleted++;
            }
        } catch (IOException e) {
            log.warn("清空副本目录失败 {}：{}", dir, e.getMessage());
        }
        return deleted;
    }

    private void requireConfirm(Map<String, Object> body, String expected) {
        String confirm = body == null || body.get("confirm") == null
                ? ""
                : String.valueOf(body.get("confirm")).trim();
        if (!expected.equals(confirm)) {
            throw new IllegalArgumentException("确认文字不对，请输入「" + expected + "」后再试");
        }
    }

    // 自检：模型通道、工具注册、经验记忆、成功率、兜底率、审计留痕。
    @GetMapping("/self-check")
    public Map<String, Object> selfCheck() {
        Map<String, Object> stats = runs.stats();
        List<Map<String, Object>> checks = new ArrayList<>();

        boolean modelReady = chatModel.getIfAvailable() != null;
        checks.add(check("model", "模型通道", modelReady ? "ok" : "warn",
                modelReady
                        ? "已接入 " + properties.getLlm().getModel() + "，工具循环可用"
                        : "未启用模型，智能体走规则工具链兜底"));

        int toolCount = tools.count();
        checks.add(check("tools", "工具注册", toolCount >= 7 ? "ok" : "fail",
                toolCount + " 个工具由 @Tool 自动发现，文档与运行时一致"));

        int memories = experience.all().size();
        int bridgeMemories = bridgeMemory.all().size();
        checks.add(check("memory", "经验记忆", memories + bridgeMemories > 0 ? "ok" : "warn",
                memories + bridgeMemories > 0
                        ? "已沉淀 " + (memories + bridgeMemories) + " 条可复用配置（标准 " + memories
                                + " / 智能体 " + bridgeMemories + "）"
                        : "还没有沉淀经验，跑成第一单后自动生成"));

        int total = number(stats.get("total"));
        int successRate = number(stats.get("success_rate"));
        checks.add(check("success", "运行成功率",
                total == 0 ? "warn" : successRate >= 90 ? "ok" : "warn",
                total == 0 ? "还没有运行记录" : successRate + "%（" + number(stats.get("ok")) + "/" + total + "）"));

        int fallbackRate = number(stats.get("fallback_rate"));
        checks.add(check("fallback", "兜底比例",
                total == 0 ? "warn" : fallbackRate <= 30 ? "ok" : "warn",
                total == 0 ? "还没有运行记录" : fallbackRate + "% 的运行由规则兜底"));

        checks.add(check("audit", "审计留痕", audit.recent(1).isEmpty() ? "warn" : "ok",
                audit.recent(1).isEmpty() ? "还没有审计记录" : "方案/任务/改结果动作都会落审计日志"));

        long okCount = checks.stream().filter(item -> "ok".equals(item.get("status"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated_at", TimeUtils.nowSecond());
        out.put("score", Math.round(okCount * 100.0 / checks.size()));
        out.put("checks", checks);
        return out;
    }

    private Map<String, Object> check(String key, String label, String status, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("status", status);
        item.put("detail", detail);
        return item;
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
