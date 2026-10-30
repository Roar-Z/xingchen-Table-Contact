package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 上传文件的元信息。真正的内容留在 uploads 目录，这里只存表头、样例和行数。
public class FileMeta {

    public String fileId;
    public String name;
    public String storedName;
    public String ext;
    public long size;
    // 内容指纹（SHA-256 十六进制）。同一个文件重复上传时靠它判定，避免列表里出现两条一样的表
    public String digest;
    public String role = "auto";
    public String roleLabel = "自动识别";
    public String path;
    public int headerRow = 1;
    public List<SheetInfo> sheets = new ArrayList<>();
    public List<String> columns = new ArrayList<>();
    public Map<String, String> sample = new LinkedHashMap<>();

    public static class SheetInfo {
        public String name;
        public int rows;
        public int columns;

        public SheetInfo() {
        }

        public SheetInfo(String name, int rows, int columns) {
            this.name = name;
            this.rows = rows;
            this.columns = columns;
        }
    }
}
