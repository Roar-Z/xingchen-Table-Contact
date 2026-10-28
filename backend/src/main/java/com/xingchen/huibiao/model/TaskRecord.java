package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次桥接任务的完整快照。为了少写一层 DTO，前端需要的字段直接放在这里。
 */
public class TaskRecord {

    public String id;
    public String name;
    public String brief;
    public String scene;
    public String sceneName;
    public String status = "queued";
    public String error;
    public String target;
    public int progress;
    public String createdAt;
    public String finishedAt;
    public long durationMs;
    public int totalRows;
    public int outputRows;
    public int anomalyRows;
    public List<SourceRef> sourceFiles = new ArrayList<>();
    public List<Artifact> artifacts = new ArrayList<>();
    public List<LogEntry> logs = new ArrayList<>();
    public List<Map<String, Object>> preview = new ArrayList<>();
    public List<String> outputColumns = new ArrayList<>();
    public List<Anomaly> anomalies = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    public Map<String, Object> stats = new LinkedHashMap<>();
    public Map<String, Object> config = new LinkedHashMap<>();
    /** 智能体在执行阶段的动作，比如“结果全为异常 → 按推荐映射重跑一次”。 */
    public List<AgentStep> agentSteps = new ArrayList<>();

    public static class SourceRef {
        public String fileId;
        public String name;
        public String role;
        public int rows;

        public SourceRef() {
        }

        public SourceRef(String fileId, String name, String role, int rows) {
            this.fileId = fileId;
            this.name = name;
            this.role = role;
            this.rows = rows;
        }
    }

    public static class Artifact {
        public String name;
        public String label;
        public String size;
        public String path;

        public Artifact() {
        }

        public Artifact(String name, String label, String size, String path) {
            this.name = name;
            this.label = label;
            this.size = size;
            this.path = path;
        }
    }

    public static class LogEntry {
        public String time;
        public String level;
        public String message;

        public LogEntry() {
        }

        public LogEntry(String time, String level, String message) {
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }
}
