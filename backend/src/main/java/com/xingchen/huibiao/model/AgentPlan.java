package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;

// 智能体配置阶段的产物：解析结果 + 执行轨迹 + 自动修补清单
public class AgentPlan {

    // rules 表示没调大模型、纯规则排的；llm 表示模型参与过
    public String mode = "rules";
    // 模型收尾时的一句话总结，规则模式是空的
    public String summary = "";
    // 结构和 /api/tasks/analyze 返回的一致，前端拿过去直接用
    public IntentResult intent;
    public List<AgentStep> steps = new ArrayList<>();
    // 智能体自己改了哪些地方，人工确认时先看这里
    public List<String> fixes = new ArrayList<>();
    // 命中了哪条历史经验，没命中就是空
    public String experienceId = "";
    public String experienceLabel = "";
    // 需要人为接手处理的提醒
    public List<String> handoff = new ArrayList<>();
    // 模型那条路失败了、靠规则兜底跑完的，就是 true
    public boolean fallback;
    public long durationMs;
    // 这一轮 token 用量，模型方没上报就是 0
    public int tokensIn;
    public int tokensOut;
    public int modelCalls;
}
