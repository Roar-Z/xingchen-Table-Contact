package com.xingchen.huibiao.service.agent;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。
 *
 * 直接从 {@link AgentService.Session} 上的 @Tool 注解反射出来，避免“文档里写的工具”
 * 和“模型真正能调的工具”对不上；以后加工具只改一处，运行面板自动出现。
 */
@Service
public class AgentToolCatalog {

    private static final List<String> ORDER = Arrays.asList(
            "inspect_sources", "detect_intent", "recall_experience",
            "apply_experience", "validate_mapping", "auto_fix", "handoff_human");

    private static final Map<String, String> PHASES = Map.of(
            "inspect_sources", "inspect",
            "detect_intent", "plan",
            "recall_experience", "recall",
            "apply_experience", "tool",
            "validate_mapping", "reflect",
            "auto_fix", "fix",
            "handoff_human", "handoff");

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Method[] methods = AgentService.Session.class.getDeclaredMethods();
        for (String name : ORDER) {
            for (Method method : methods) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null || !name.equals(tool.name())) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", tool.name());
                item.put("phase", PHASES.getOrDefault(tool.name(), "tool"));
                item.put("description", tool.value());
                out.add(item);
            }
        }
        return out;
    }

    public int count() {
        return list().size();
    }
}
