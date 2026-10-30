package com.xingchen.huibiao.model;

// 异常行。row 是源表里的实际行号（从 1 开始），方便业务人员回原表核对。
public record Anomaly(int row, String source, String reason, String raw, String type) {

    public Anomaly(int row, String source, String reason, String raw) {
        this(row, source, reason, raw, "数据异常");
    }
}
