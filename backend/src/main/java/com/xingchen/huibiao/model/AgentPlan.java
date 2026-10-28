package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体配置阶段的产出：解析结果 + 完整执行轨迹 + 自动修正清单。
 *
 * 前端拿到它就能同时渲染“意图卡”“映射表”和“智能体轨迹”，
 * 不用再单独查一次日志。
 */
public class AgentPlan {

    /** rules=没用上大模型，纯规则编排；llm=语义模型参与。 */
    public String mode = "rules";
    /** 模型跑完工具循环后的收尾说明，规则模式为空。 */
    public String summary = "";
    /** 与 /api/tasks/analyze 完全一致的结构，前端原有逻辑不用改。 */
    public IntentResult intent;
    /** 智能体做了什么、依据是什么。 */
    public List<AgentStep> steps = new ArrayList<>();
    /** 智能体自动修正的地方，需要人工确认时优先看这里。 */
    public List<String> fixes = new ArrayList<>();
    /** 命中的历史经验，没有命中为空。 */
    public String experienceId = "";
    public String experienceLabel = "";
    /** 交给人工确认的待办提醒。 */
    public List<String> handoff = new ArrayList<>();
    /** 模型工具循环失败、由规则工具链兜底完成时为 true。 */
    public boolean fallback;
    /** 配置阶段总耗时，毫秒。 */
    public long durationMs;
    /** 本轮模型 token 用量；供应商未上报时为 0。 */
    public int tokensIn;
    public int tokensOut;
    /** 本轮实际发生的模型调用次数。 */
    public int modelCalls;
}
