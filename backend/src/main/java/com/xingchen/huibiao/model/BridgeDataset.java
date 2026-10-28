package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体模式（跨系统桥接）的现场：一次上传的整个数据集、规划方案、执行结果都在这里。
 * 结构直接写进 data/bridge/datasets/&lt;id&gt;/meta.json，刷新页面能接着看。
 */
public class BridgeDataset {

    public String id;
    public String name;
    /** zip / folder / files */
    public String sourceType = "files";
    public String createdAt;
    /** scanned / planned / running / done / failed */
    public String status = "scanned";
    public String instruction = "";
    public List<Table> tables = new ArrayList<>();
    public Plan plan;
    public Result result;
    public List<String> notes = new ArrayList<>();

    /** 一张被扫描出来的表：叫什么、属于哪个系统、表头和样例 */
    public static class Table {
        public String id;
        public String file;
        public String stored;
        public String kind = "unknown";
        public String kindLabel = "普通表";
        public int rows;
        public List<String> columns = new ArrayList<>();
        public Map<String, String> sample = new LinkedHashMap<>();
    }

    /** 规划方案：全部字段都由智能体生成，用户核对后才会执行 */
    public static class Plan {
        public String summary;
        public List<Step> steps = new ArrayList<>();
        public List<Source> sources = new ArrayList<>();
        public List<Align> aligns = new ArrayList<>();
        public List<Compute> computes = new ArrayList<>();
        public List<String> filters = new ArrayList<>();
        /** 展示用的异常规则文案，和 anomalyChecks 一一对应 */
        public List<String> anomalyRules = new ArrayList<>();
        /** 执行时真正逐条跑的异常检查，规划器和模型都往这里写 */
        public List<AnomalyCheck> anomalyChecks = new ArrayList<>();
        public List<String> outputColumns = new ArrayList<>();
        public String joinKey;
        public String joinLabel;
        /** 执行时用到的结构化筛选条件，界面上用 filters 的文字描述展示 */
        public String dateField;
        public String dateFrom;
        public String dateTo;
        public String statusFilter;
        public List<String> notes = new ArrayList<>();
        /** rules / llm */
        public String mode = "rules";
        public boolean confirmed;
        public String plannedAt;
    }

    public static class Step {
        public String tool;
        public String title;
        public String detail;
        public String state = "done";
        public long ms;
    }

    public static class Source {
        public String tableId;
        public String role;
        public String reason;
    }

    /** 一个字段对齐：标准字段 ← 某张表的某一列 */
    public static class Align {
        public String field;
        public String label;
        public String tableId;
        public String column;
        /** 规则 / AI / 自定义 */
        public String origin = "规则";
        public double confidence = 0.85;
    }

    public static class Compute {
        public String name;
        public List<String> plus = new ArrayList<>();
        public List<String> minus = new ArrayList<>();
        public String origin = "规则";
    }

    /**
     * 一条能真正执行的异常检查：type 决定怎么判，label 指向标准字段，reason 是写进异常清单的说明。
     * join 是跨表检查，label 为空；duplicate 按关联键判断。
     */
    public static class AnomalyCheck {
        /** join / blank / date / numeric / garbled / duplicate */
        public String type;
        public String label = "";
        public String reason;

        public AnomalyCheck() {
        }

        public AnomalyCheck(String type, String label, String reason) {
            this.type = type;
            this.label = label == null ? "" : label;
            this.reason = reason;
        }
    }

    public static class Result {
        public int totalRows;
        public int okRows;
        public int anomalyRows;
        public int tableCount;
        public List<String> columns = new ArrayList<>();
        public List<Map<String, String>> preview = new ArrayList<>();
        public List<Map<String, String>> anomalyPreview = new ArrayList<>();
        public List<String> artifacts = new ArrayList<>();
        public long elapsedMs;
        public String finishedAt;
        public String mode = "rules";
    }
}
