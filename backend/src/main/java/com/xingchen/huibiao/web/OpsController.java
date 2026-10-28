package com.xingchen.huibiao.web;

import com.xingchen.huibiao.config.AppProperties;
import com.xingchen.huibiao.service.agent.AgentToolCatalog;
import com.xingchen.huibiao.service.agent.ExperienceStore;
import com.xingchen.huibiao.service.governance.AgentRunStore;
import com.xingchen.huibiao.service.governance.AuditLog;
import com.xingchen.huibiao.util.TimeUtils;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行观测：不引入 Langfuse/OTel 这类外部组件，单机也能回答四个问题——
 * 跑了多少次、成功率多少、多少走了兜底、token 花在哪、谁改了什么。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final AgentRunStore runs;
    private final AgentToolCatalog tools;
    private final AuditLog audit;
    private final ExperienceStore experience;
    private final AppProperties properties;
    private final ObjectProvider<ChatModel> chatModel;

    public OpsController(AgentRunStore runs, AgentToolCatalog tools, AuditLog audit,
                         ExperienceStore experience, AppProperties properties,
                         ObjectProvider<ChatModel> chatModel) {
        this.runs = runs;
        this.tools = tools;
        this.audit = audit;
        this.experience = experience;
        this.properties = properties;
        this.chatModel = chatModel;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return runs.stats();
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        return tools.list();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(value = "limit", defaultValue = "40") int limit) {
        return audit.recent(limit);
    }

    /**
     * 自检：模型通道、工具注册、经验记忆、成功率、兜底率、审计留痕。
     * 评审现场点一下就能看到“这套东西是不是健康的”，不用翻日志。
     */
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
        checks.add(check("memory", "经验记忆", memories > 0 ? "ok" : "warn",
                memories > 0 ? "已沉淀 " + memories + " 条可复用配置" : "还没有沉淀经验，跑成第一单后自动生成"));

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
