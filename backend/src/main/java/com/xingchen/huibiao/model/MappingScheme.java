package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 可复用的字段映射方案。存的是“目标列 → 源文件名 / 源列名”，
 * 不存内部 fileId，换机器或重新上传同名文件也能直接套用。
 */
public class MappingScheme {

    public String id;
    public String name;
    public String scene;
    public String templateName;
    public String savedAt;
    public List<Entry> mapping = new ArrayList<>();

    public static class Entry {
        public String target;
        public String fileName;
        public String column;
        /** 拼接表达式，方案里也一并保存，换人加载后能直接复用。 */
        public String expr;

        public Entry() {
        }

        public Entry(String target, String fileName, String column) {
            this(target, fileName, column, "");
        }

        public Entry(String target, String fileName, String column, String expr) {
            this.target = target;
            this.fileName = fileName;
            this.column = column;
            this.expr = expr;
        }
    }
}
