package com.xingchen.huibiao.web;

import com.xingchen.huibiao.dto.ApiRequests.AnalyzeRequest;
import com.xingchen.huibiao.model.AgentPlan;
import com.xingchen.huibiao.service.agent.AgentService;
import com.xingchen.huibiao.service.agent.ExperienceStore;
import com.xingchen.huibiao.service.governance.AgentRunStore;
import com.xingchen.huibiao.service.governance.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 智能体入口。和 /api/tasks/analyze 的区别是它多返回一段执行轨迹，
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agent;
    private final ExperienceStore experience;
    private final AgentRunStore runs;
    private final AuditLog audit;

    public AgentController(AgentService agent, ExperienceStore experience,
                           AgentRunStore runs, AuditLog audit) {
        this.agent = agent;
        this.experience = experience;
        this.runs = runs;
        this.audit = audit;
    }

    @PostMapping("/plan")
    public AgentPlan plan(@RequestBody AnalyzeRequest request) {
        long started = System.currentTimeMillis();
        int fileCount = request == null || request.files() == null ? 0 : request.files().size();
        try {
            AgentPlan plan = agent.plan(request);
            runs.record(plan, fileCount, plan.durationMs);
            audit.record("agent.plan", targetOf(plan), detailOf(plan),
                    metaOf(plan));
            return plan;
        } catch (RuntimeException e) {
            runs.recordFailure(e.getMessage(), fileCount, System.currentTimeMillis() - started);
            audit.record("agent.plan.failed", fileCount + " 个源表", e.getMessage());
            throw e;
        }
    }

    @GetMapping("/experience")
    public List<Map<String, Object>> experience() {
        return experience.summary();
    }

    @DeleteMapping("/experience/{id}")
    public Map<String, Object> forget(@PathVariable("id") String id) {
        if (!experience.remove(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这条经验记录");
        }
        audit.record("agent.experience.delete", id, "手动删除一条经验记忆");
        return Map.of("removed", 1);
    }

    private String targetOf(AgentPlan plan) {
        if (plan.intent == null) {
            return "配置编排";
        }
        String template = plan.intent.targetTemplate == null ? "未识别模板" : plan.intent.targetTemplate.name();
        return plan.intent.sceneName + " → " + template;
    }

    private String detailOf(AgentPlan plan) {
        int mappings = plan.intent == null || plan.intent.mapping == null ? 0 : plan.intent.mapping.size();
        String mode = "llm".equals(plan.mode) ? "模型自主编排" : "规则工具链";
        if (plan.fallback) {
            mode = "规则兜底";
        }
        return mode + "，工具 " + plan.steps.size() + " 步，映射 " + mappings + " 条，自动修正 "
                + plan.fixes.size() + " 处，耗时 " + Math.round(plan.durationMs / 100.0) / 10.0
                + "s，token " + (plan.tokensIn + plan.tokensOut)
                + (plan.experienceId.isEmpty() ? "" : "，复用经验 " + plan.experienceId);
    }

    private Map<String, Object> metaOf(AgentPlan plan) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", plan.mode);
        meta.put("fallback", plan.fallback);
        meta.put("tokens_in", plan.tokensIn);
        meta.put("tokens_out", plan.tokensOut);
        meta.put("mappings", plan.intent == null || plan.intent.mapping == null ? 0 : plan.intent.mapping.size());
        return meta;
    }
}
