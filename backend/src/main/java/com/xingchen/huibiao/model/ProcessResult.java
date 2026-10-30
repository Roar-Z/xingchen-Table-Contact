package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 试算或执行的结果放在内存里。preview 只留前 20 行给页面看，全量 rows 留给导出
public class ProcessResult {

    public List<String> outputColumns = new ArrayList<>();
    public List<Map<String, Object>> rows = new ArrayList<>();
    public List<Map<String, Object>> preview = new ArrayList<>();
    public List<Anomaly> anomalies = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    public Map<String, Object> stats = new LinkedHashMap<>();
    public Map<String, Object> rules = new LinkedHashMap<>();
    public int totalRows;
    public int outputRows;
    public int anomalyRows;

    public void addRow(Map<String, Object> row) {
        rows.add(row);
        if (preview.size() < 20) {
            preview.add(row);
        }
    }

    public void finish() {
        outputRows = rows.size();
        anomalyRows = anomalies.size();
    }
}
