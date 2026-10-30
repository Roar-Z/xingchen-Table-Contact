package com.xingchen.huibiao.model;

// 一条异常行。row 用源表里的真实行号（从 1 开始）
public record Anomaly(int row, String source, String reason, String raw, String type) {

    public Anomaly(int row, String source, String reason, String raw) {
        this(row, source, reason, raw, "数据异常");
    }
}
