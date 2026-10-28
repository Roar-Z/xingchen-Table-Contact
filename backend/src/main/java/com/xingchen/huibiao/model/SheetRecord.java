package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「表格智作」里的一张表。
 *
 * 数据本体是 data/sheets/{id}.xlsx，这里只存元数据和操作日志，
 * 这样用户下载到的文件和页面上看到的是同一份，也方便直接把文件拷走。
 */
public class SheetRecord {

    public String id;
    public String name;
    /** upload：上传；template：模板；nl：一句话新建；merge：多表融合 */
    public String origin = "upload";
    public String originalName;
    public String note;
    public String createdAt;
    public String updatedAt;
    public List<String> columns = new ArrayList<>();
    public int rowCount;
    public int revisionCount;
    public String lastSummary;
    /** 最近一次修改的快照还在，可以撤销一次 */
    public boolean undoAvailable;
    /** 快照里的行数：空行在 xlsx 里没有内容，撤销后按这个数补回来 */
    public int undoRowCount;
    /** 修改记录，只用于展示和撤销提示，真正的数据在 xlsx 里 */
    public List<Map<String, Object>> revisions = new ArrayList<>();
    public List<LogEntry> logs = new ArrayList<>();

    public static class LogEntry {
        public String time;
        public String action;
        public String detail;
        /** ok / info / warn */
        public String level = "info";

        public LogEntry() {
        }

        public LogEntry(String time, String action, String detail, String level) {
            this.time = time;
            this.action = action;
            this.detail = detail;
            this.level = level;
        }
    }

    public void log(String action, String detail, String level) {
        logs.add(0, new LogEntry(com.xingchen.huibiao.util.TimeUtils.nowSecond(), action, detail, level));
        while (logs.size() > 80) {
            logs.remove(logs.size() - 1);
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("origin", origin);
        out.put("original_name", originalName);
        out.put("note", note);
        out.put("created_at", createdAt);
        out.put("updated_at", updatedAt);
        out.put("columns", columns);
        out.put("row_count", rowCount);
        out.put("column_count", columns == null ? 0 : columns.size());
        out.put("revision_count", revisionCount);
        out.put("last_summary", lastSummary);
        out.put("undo_available", undoAvailable);
        out.put("log_count", logs == null ? 0 : logs.size());
        return out;
    }
}
