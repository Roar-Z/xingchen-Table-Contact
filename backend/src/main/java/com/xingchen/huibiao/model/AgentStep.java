package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;

// 智能体每走一步记一条，前端拿这串记录当过程回放看
public class AgentStep {

    // 第几步，从 1 开始
    public int index;
    // inspect 读表 / recall 翻记忆 / plan 规划 / tool 执行 / reflect 反思 / fix 修补 / handoff 转人工
    public String phase = "tool";
    // 标题一句话，比如“读取源表结构”；detail 是下面的补充说明
    public String title;
    public String detail;
    public String tool;
    // ok / warn / fail / skip
    public String status = "ok";
    // 这一步花了多少毫秒
    public long elapsedMs;
    // 明细条目，比如“3 个文件、12 行、6 列”
    public List<String> items = new ArrayList<>();

    public AgentStep() {
    }

    public AgentStep(String phase, String title, String detail, String tool, String status) {
        this.phase = phase;
        this.title = title;
        this.detail = detail;
        this.tool = tool;
        this.status = status;
    }

    public AgentStep item(String text) {
        if (text != null && !text.isBlank()) {
            items.add(text);
        }
        return this;
    }
}
