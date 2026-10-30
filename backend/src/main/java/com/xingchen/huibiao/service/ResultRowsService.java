package com.xingchen.huibiao.service;

import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.TaskRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

// 结果表的分页查询。
@Service
public class ResultRowsService {

    private static final Logger log = LoggerFactory.getLogger(ResultRowsService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern NUMBER_TEXT = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_CACHE = 8;

    private final TaskStore store;
    private final DataPaths paths;

    // 最多缓存 8 个任务的结果，够页面来回切；超过就淘汰最久没用的。
    private final Map<String, Snapshot> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
            return size() > MAX_CACHE;
        }
    };

    public ResultRowsService(TaskStore store, DataPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    public Map<String, Object> page(String taskId, int page, int pageSize, String keyword, String column) {
        TaskRecord task = store.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("没有找到任务 " + taskId));
        Snapshot snapshot = snapshotOf(task);

        int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int current = Math.max(1, page);
        List<String> tokens = tokensOf(keyword);
        int columnIndex = indexOf(snapshot.columns, column);

        List<List<String>> matched = new ArrayList<>();
        for (List<String> row : snapshot.rows) {
            if (matches(row, tokens, columnIndex)) {
                matched.add(row);
            }
        }

        int total = matched.size();
        int pages = Math.max(1, (total + size - 1) / size);
        current = Math.min(current, pages);
        int from = Math.min(total, (current - 1) * size);
        int to = Math.min(total, from + size);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> row : matched.subList(from, to)) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (int i = 0; i < snapshot.columns.size(); i++) {
                item.put(snapshot.columns.get(i), i < row.size() ? row.get(i) : "");
            }
            rows.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", snapshot.columns);
        out.put("rows", rows);
        out.put("total", total);
        out.put("total_rows", snapshot.rows.size());
        out.put("page", current);
        out.put("page_size", size);
        out.put("pages", pages);
        out.put("numeric_columns", snapshot.numericColumns);
        out.put("partial", snapshot.partial);
        out.put("source", snapshot.source);
        out.put("file_name", snapshot.fileName);
        out.put("updated_at", snapshot.updatedAt);
        return out;
    }

    private Snapshot snapshotOf(TaskRecord task) {
        Path file = preferredArtifact(task);
        if (file == null) {
            return previewSnapshot(task);
        }
        String key = file.toString() + "|" + task.id;
        try {
            String stamp = Files.getLastModifiedTime(file).toMillis() + "|" + Files.size(file);
            synchronized (cache) {
                Snapshot cached = cache.get(key);
                if (cached != null && stamp.equals(cached.stamp)) {
                    return cached;
                }
            }
            Snapshot parsed = parse(file);
            parsed.stamp = stamp;
            synchronized (cache) {
                cache.put(key, parsed);
            }
            return parsed;
        } catch (Exception e) {
            log.warn("结果表读取失败，退回任务快照预览：{}", e.getMessage());
            return previewSnapshot(task);
        }
    }

    // 优先读 .xlsx（POI 打开快、没有 65535 行限制），没有再用 .xls；异常日志不参与分页。
    private Path preferredArtifact(TaskRecord task) {
        if (task.artifacts == null || task.artifacts.isEmpty()) {
            return null;
        }
        TaskRecord.Artifact chosen = null;
        for (TaskRecord.Artifact artifact : task.artifacts) {
            if (artifact.name == null || artifact.name.startsWith("异常日志")) {
                continue;
            }
            String lower = artifact.name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xlsx")) {
                chosen = artifact;
                break;
            }
            if (chosen == null && lower.endsWith(".xls")) {
                chosen = artifact;
            }
        }
        if (chosen == null) {
            return null;
        }
        Path path = resolve(chosen.path);
        return Files.isRegularFile(path) ? path : null;
    }

    private Snapshot parse(Path file) throws Exception {
        Snapshot snapshot = new Snapshot();
        snapshot.fileName = file.getFileName().toString();
        snapshot.source = "excel";
        snapshot.updatedAt = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(),
                ZoneId.systemDefault()).format(STAMP);

        try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.US);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            int first = Math.max(0, sheet.getFirstRowNum());
            Row head = sheet.getRow(first);
            int width = head == null ? 0 : Math.max(0, head.getLastCellNum());
            snapshot.columns = headersOf(head, width, formatter);
            if (snapshot.columns.isEmpty()) {
                return snapshot;
            }

            for (int r = first + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>(width);
                boolean any = false;
                for (int c = 0; c < width; c++) {
                    String value = cellText(row == null ? null : row.getCell(c), formatter, evaluator);
                    if (!value.isEmpty()) {
                        any = true;
                    }
                    cells.add(value);
                }
                if (any) {
                    snapshot.rows.add(cells);
                }
            }
        }
        snapshot.numericColumns = numericColumnsOf(snapshot.columns, snapshot.rows);
        return snapshot;
    }

    private List<String> headersOf(Row head, int width, DataFormatter formatter) {
        List<String> headers = new ArrayList<>();
        if (head == null) {
            return headers;
        }
        Set<String> used = new LinkedHashSet<>();
        for (int c = 0; c < width; c++) {
            String base = cellText(head.getCell(c), formatter, null).trim();
            if (base.isEmpty()) {
                base = "列" + (c + 1);
            }
            String name = base;
            int n = 2;
            while (used.contains(name)) {
                name = base + " (" + n + ")";
                n++;
            }
            used.add(name);
            headers.add(name);
        }
        return headers;
    }

    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            String value = formatter.formatCellValue(cell, evaluator);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> numericColumnsOf(List<String> columns, List<List<String>> rows) {
        List<String> numeric = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            boolean hasValue = false;
            boolean allNumber = true;
            for (List<String> row : rows) {
                String value = c < row.size() ? row.get(c) : "";
                if (value.isEmpty()) {
                    continue;
                }
                hasValue = true;
                if (!NUMBER_TEXT.matcher(value).matches()) {
                    allNumber = false;
                    break;
                }
            }
            if (hasValue && allNumber) {
                numeric.add(columns.get(c));
            }
        }
        return numeric;
    }

    // 预览快照：导出文件被清理时至少还能搜前 20 行，页面上会标明数据不完整。
    private Snapshot previewSnapshot(TaskRecord task) {
        Snapshot snapshot = new Snapshot();
        snapshot.source = "preview";
        List<Map<String, Object>> preview = task.preview == null ? List.of() : task.preview;
        snapshot.partial = task.outputRows > preview.size();
        snapshot.updatedAt = task.finishedAt == null ? "" : task.finishedAt;
        snapshot.fileName = "";
        snapshot.columns = task.outputColumns == null ? new ArrayList<>() : new ArrayList<>(task.outputColumns);
        snapshot.rows = new ArrayList<>();
        for (Map<String, Object> row : preview) {
            List<String> cells = new ArrayList<>();
            for (String column : snapshot.columns) {
                Object value = row.get(column);
                cells.add(value == null ? "" : String.valueOf(value));
            }
            snapshot.rows.add(cells);
        }
        snapshot.numericColumns = numericColumnsOf(snapshot.columns, snapshot.rows);
        return snapshot;
    }

    private List<String> tokensOf(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keyword.trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    private int indexOf(List<String> columns, String column) {
        if (column == null || column.isBlank() || "all".equalsIgnoreCase(column)) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (column.equals(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean matches(List<String> row, List<String> tokens, int columnIndex) {
        if (tokens.isEmpty()) {
            return true;
        }
        for (String token : tokens) {
            boolean hit = false;
            if (columnIndex >= 0) {
                String value = columnIndex < row.size() ? row.get(columnIndex) : "";
                hit = value.toLowerCase(Locale.ROOT).contains(token);
            } else {
                for (String value : row) {
                    if (value != null && value.toLowerCase(Locale.ROOT).contains(token)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private Path resolve(String relative) {
        String clean = relative == null ? "" : relative.replace('\\', '/');
        Path parent = paths.root().getParent();
        if (parent != null) {
            Path candidate = parent.resolve(clean).normalize();
            if (candidate.startsWith(paths.root())) {
                return candidate;
            }
        }
        return paths.root().resolve(clean).normalize();
    }

    private static class Snapshot {
        private String stamp = "";
        private String fileName = "";
        private String updatedAt = "";
        private String source = "excel";
        private boolean partial = false;
        private List<String> columns = new ArrayList<>();
        private List<String> numericColumns = new ArrayList<>();
        private List<List<String>> rows = new ArrayList<>();
    }
}
