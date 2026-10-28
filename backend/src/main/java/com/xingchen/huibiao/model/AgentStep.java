package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体的一步操作记录（轨迹）。
 *
 * 和普通日志的区别：每一步都说明“用了哪个工具、依据什么、得出什么结论”，
 * 前端按时间线展示，评审能看到智能体确实是先看数据、再定方案、校验后再交人的。
 */
public class AgentStep {

    /** 步骤序号，从 1 开始。 */
    public int index;
    /** 阶段：inspect 读取 / recall 记忆 / plan 规划 / tool 执行 / reflect 反思 / fix 修复 / handoff 交人。 */
    public String phase = "tool";
    /** 一句话标题，例如“读取源表结构”。 */
    public String title;
    /** 结论或依据，前端展示在标题下面。 */
    public String detail;
    /** 工具名，例如 inspect_sources、recall_experience、validate_mapping。 */
    public String tool;
    /** ok / warn / fail / skip。 */
    public String status = "ok";
    /** 这步花了多久，毫秒。 */
    public long elapsedMs;
    /** 结构化明细，比如“文件、行数、列数”“缺失的必填列”。 */
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
