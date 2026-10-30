package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 一次“用自然语言改结果”的记录。
public class ResultRevision {

    public String id;
    public String instruction;
    // 智能体把这句话理解成了什么，一句话。
    public String summary;
    // llm：语义模型听懂；rules：按内置句式兜底。
    public String mode = "rules";
    public String createdAt;
    public int changedRows;
    public List<Map<String, Object>> ops = new ArrayList<>();
}
