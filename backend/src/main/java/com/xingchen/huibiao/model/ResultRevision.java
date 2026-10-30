package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 用自然语言改结果的一次记录
public class ResultRevision {

    public String id;
    public String instruction;
    // 这句话被理解成了什么，一句话说清
    public String summary;
    // llm 表示模型听懂了，rules 表示按内置句式走
    public String mode = "rules";
    public String createdAt;
    public int changedRows;
    public List<Map<String, Object>> ops = new ArrayList<>();
}
