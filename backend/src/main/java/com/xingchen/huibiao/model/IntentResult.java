package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析结果。前端第二步的意图卡、第三步的映射建议都从这里取。
 */
public class IntentResult {

    public String scene;
    public String sceneName;
    public double confidence;
    public String parser = "rules";
    public List<String> joinKeys = new ArrayList<>();
    public List<String> filters = new ArrayList<>();
    public String calculation;
    public String department;
    public String startDate;
    public String endDate;
    public TemplateItem targetTemplate;
    public List<String> notes = new ArrayList<>();
    public List<SourceGroup> sourceColumns = new ArrayList<>();
    public List<MappingSuggestion> mapping = new ArrayList<>();

    public static class SourceGroup {
        public String fileId;
        public String name;
        public String role;
        public List<String> columns = new ArrayList<>();
        public Map<String, String> sample = new LinkedHashMap<>();

        public SourceGroup() {
        }

        public SourceGroup(String fileId, String name, String role, List<String> columns, Map<String, String> sample) {
            this.fileId = fileId;
            this.name = name;
            this.role = role;
            this.columns = columns;
            this.sample = sample;
        }
    }

    public static class MappingSuggestion {
        public String target;
        public boolean required;
        public String fileId;
        public String column;
        public double confidence;
        /** 这条建议的来源：rules=词典规则，ai=语义模型。前端据此区分展示。 */
        public String origin = "rules";
        /** 派生表达式：源表列不够用时由多列拼接/常量/换算得到，例如 {起始日期} {起始时间}。 */
        public String expression;

        public MappingSuggestion() {
        }

        public MappingSuggestion(String target, boolean required, String fileId, String column, double confidence) {
            this(target, required, fileId, column, confidence, "rules");
        }

        public MappingSuggestion(String target, boolean required, String fileId, String column,
                                 double confidence, String origin) {
            this.target = target;
            this.required = required;
            this.fileId = fileId;
            this.column = column;
            this.confidence = confidence;
            this.origin = origin;
        }
    }
}
