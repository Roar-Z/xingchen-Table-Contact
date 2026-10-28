package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次“用自然语言改结果”的记录。
 *
 * 用户原话和智能体理解出的操作一起落盘：重新执行时按顺序重放，任务详情也能看到改过什么。
 * ops 用 Map 表达，接口和前端不用为每种操作再写一套 DTO。
 */
public class ResultRevision {

    public String id;
    public String instruction;
    /** 智能体把这句话理解成了什么，一句话。 */
    public String summary;
    /** llm：语义模型听懂；rules：按内置句式兜底。 */
    public String mode = "rules";
    public String createdAt;
    public int changedRows;
    public List<Map<String, Object>> ops = new ArrayList<>();
}
