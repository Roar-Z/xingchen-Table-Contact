package com.xingchen.huibiao.service.sheet;

import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.SheetRecord;
import com.xingchen.huibiao.model.TaskRecord;
import com.xingchen.huibiao.service.sheet.SheetFile.Table;
import com.xingchen.huibiao.util.TextUtils;
import com.xingchen.huibiao.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 表格智作的核心服务：上传、建表、融合、自然语言改表、单元格编辑都在这里。
@Service
public class SheetService {

    private static final Logger log = LoggerFactory.getLogger(SheetService.class);
    private static final int MAX_REVISIONS = 30;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_CACHE = 10;

    private final SheetStore store;
    private final SheetAgentService agent;
    private final DataPaths paths;

    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHE;
        }
    };

    public SheetService(SheetStore store, SheetAgentService agent, DataPaths paths) {
        this.store = store;
        this.agent = agent;
        this.paths = paths;
    }

    // ==================== 查询 ====================

    public List<Map<String, Object>> list(String keyword) {
        String text = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SheetRecord record : store.all()) {
            if (!text.isEmpty()) {
                String haystack = (record.name + " " + String.join(" ", record.columns)).toLowerCase(Locale.ROOT);
                if (!haystack.contains(text)) {
                    continue;
                }
            }
            out.add(record.summary());
        }
        return out;
    }

    // 仓库列表的服务端分页：默认每页 10 张，和市面主流表格的翻页习惯一致。
    public Map<String, Object> page(String keyword, int page, int pageSize) {
        List<Map<String, Object>> all = list(keyword);
        int size = Math.max(1, Math.min(pageSize, 50));
        int total = all.size();
        int pages = Math.max(1, (total + size - 1) / size);
        int current = Math.min(Math.max(1, page), pages);
        int from = Math.min(total, (current - 1) * size);
        int to = Math.min(total, from + size);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", new ArrayList<>(all.subList(from, to)));
        out.put("page", current);
        out.put("pages", pages);
        out.put("page_size", size);
        out.put("total", total);

        List<Map<String, Object>> whole = list(null);
        long rows = 0;
        long revisions = 0;
        for (Map<String, Object> item : whole) {
            rows += numberOf(item.get("row_count"));
            revisions += numberOf(item.get("revision_count"));
        }
        out.put("sheet_total", whole.size());
        out.put("row_total", rows);
        out.put("revision_total", revisions);
        return out;
    }

    private long numberOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public Map<String, Object> detail(String id) {
        SheetRecord record = store.require(id);
        Map<String, Object> out = new LinkedHashMap<>(record.summary());
        out.put("revisions", record.revisions);
        out.put("logs", record.logs);
        return out;
    }

    public List<Map<String, Object>> templates() {
        return SheetTemplates.items();
    }

    // 仓库操作日志：所有表格的日志合在一起按时间倒序，服务端分页。
    public Map<String, Object> logs(int page, int pageSize) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (SheetRecord record : store.all()) {
            for (SheetRecord.LogEntry entry : record.logs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", entry.id);
                item.put("sheet_id", record.id);
                item.put("sheet_name", record.name);
                item.put("time", entry.time);
                item.put("action", entry.action);
                item.put("detail", entry.detail);
                item.put("level", entry.level);
                all.add(item);
            }
        }
        // 已经删掉的表也要出现在操作日志里，否则用户删完表就看不到「删除表格」这件事了
        for (SheetStore.ArchivedLog entry : store.archived()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.id);
            item.put("sheet_id", entry.sheetId);
            item.put("sheet_name", entry.sheetName);
            item.put("time", entry.time);
            item.put("action", entry.action);
            item.put("detail", entry.detail);
            item.put("level", entry.level);
            item.put("deleted", true);
            all.add(item);
        }
        all.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("time"))).reversed());

        int size = Math.max(1, Math.min(pageSize, 50));
        int total = all.size();
        int pages = Math.max(1, (total + size - 1) / size);
        int current = Math.min(Math.max(1, page), pages);
        int from = Math.min(total, (current - 1) * size);
        int to = Math.min(total, from + size);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", new ArrayList<>(all.subList(from, to)));
        out.put("page", current);
        out.put("pages", pages);
        out.put("page_size", size);
        out.put("total", total);
        out.put("keep", SheetStore.MAX_LOGS);
        return out;
    }

    // 删日志：ids 为空表示清空全部，否则只删勾中的那几条。
    public Map<String, Object> deleteLogs(List<String> ids) {
        Set<String> target = new HashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    target.add(id);
                }
            }
        }
        boolean clearAll = target.isEmpty();
        int deleted = 0;
        for (SheetRecord record : store.all()) {
            if (clearAll) {
                deleted += record.logs.size();
                record.logs = new ArrayList<>();
            } else {
                int before = record.logs.size();
                record.logs.removeIf(entry -> target.contains(entry.id));
                deleted += before - record.logs.size();
            }
        }
        // 已删除表格留下的「删除表格」日志也要能被勾选删掉
        int archivedBefore = store.archived().size();
        store.removeArchived(target, clearAll);
        deleted += clearAll ? archivedBefore : archivedBefore - store.archived().size();
        store.save();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        return out;
    }

    // 分页 + 模糊查询。和任务结果表保持同一套返回结构，前端复用同一个表格组件。
    public Map<String, Object> page(String id, int page, int pageSize, String keyword, String column) {
        SheetRecord record = store.require(id);
        Table table = cachedTable(record);

        int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int current = Math.max(1, page);
        List<String> tokens = tokensOf(keyword);
        int columnIndex = indexOf(table.columns, column);

        List<List<String>> matched = new ArrayList<>();
        for (List<String> row : table.rows) {
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
            for (int i = 0; i < table.columns.size(); i++) {
                item.put(table.columns.get(i), i < row.size() ? row.get(i) : "");
            }
            rows.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", table.columns);
        out.put("rows", rows);
        out.put("total", total);
        out.put("total_rows", table.rows.size());
        out.put("page", current);
        out.put("page_size", size);
        out.put("pages", pages);
        out.put("numeric_columns", SheetFile.numericColumns(table));
        out.put("partial", false);
        out.put("source", "excel");
        out.put("file_name", record.name + ".xlsx");
        out.put("updated_at", record.updatedAt);
        return out;
    }

    public Path fileOf(String id) {
        SheetRecord record = store.require(id);
        Path path = paths.sheetPath(record.id);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("这张表的数据文件不见了，可能已被清理");
        }
        return path;
    }

    // ==================== 新建 ====================

    public List<Map<String, Object>> upload(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("没有收到文件");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            Table table = readUpload(file);
            if (table.columns.isEmpty()) {
                throw new IllegalArgumentException("「" + file.getOriginalFilename() + "」里没有读到表头");
            }
            SheetRecord record = newRecord(SheetFile.titleOf(file.getOriginalFilename()), "upload");
            record.originalName = file.getOriginalFilename();
            record.log("上传", "从 " + file.getOriginalFilename() + " 读入 " + table.rows.size() + " 行", "ok");
            save(record, table);
            out.add(record.summary());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("没有可用的表格");
        }
        return out;
    }

    public Map<String, Object> fromTemplate(String templateId, String name) {
        Map<String, Object> template = SheetTemplates.find(templateId);
        List<String> columns = toStrings(template.get("columns"));
        List<List<String>> sample = toRows(template.get("sample"));
        Table table = new Table(columns, new ArrayList<>());
        for (List<String> row : sample) {
            List<String> cells = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                cells.add(i < row.size() ? row.get(i) : "");
            }
            table.rows.add(cells);
        }
        String title = name == null || name.isBlank() ? String.valueOf(template.get("name")) : name.trim();
        SheetRecord record = newRecord(title, "template");
        record.note = "按参考模板创建，示例行可以直接改掉";
        record.log("模板", "按「" + template.get("name") + "」建表，示例 " + table.rows.size() + " 行", "ok");
        save(record, table);
        return detail(record.id);
    }

    public Map<String, Object> blank(String name, List<String> columns) {
        List<String> names = new ArrayList<>();
        if (columns != null) {
            for (String column : columns) {
                if (column != null && !column.isBlank()) {
                    names.add(column.trim());
                }
            }
        }
        if (names.size() < 2) {
            throw new IllegalArgumentException("至少要两列，比如 姓名、部门");
        }
        String title = name == null || name.isBlank() ? "新建表格" : name.trim();
        SheetRecord record = newRecord(title, "nl");
        record.log("新建", "空白表 " + names.size() + " 列", "ok");
        save(record, new Table(names, new ArrayList<>()));
        return detail(record.id);
    }

    // 把智能体任务的产出表收进「我的仓库」：任务一跑完，结果表就能在这里继续改、继续下载。
    public Map<String, Object> archiveFromTask(TaskRecord task) {
        if (task == null || task.id == null || task.id.isBlank()) {
            throw new IllegalArgumentException("缺少任务号，无法归档");
        }
        Path source = outputWorkbookOf(task);
        Table table;
        try {
            table = SheetFile.read(source);
        } catch (IOException e) {
            throw new IllegalStateException("任务产出表读取失败：" + e.getMessage(), e);
        }
        if (table.columns.isEmpty()) {
            throw new IllegalArgumentException("任务产出表没有表头，跳过归档");
        }
        SheetRecord record = store.all().stream()
                .filter(item -> task.id.equals(item.taskId))
                .findFirst()
                .orElse(null);
        boolean exists = record != null;
        if (!exists) {
            record = newRecord(task.name, "task");
            record.taskId = task.id;
            record.originalName = source.getFileName().toString();
        }
        record.note = "任务 " + task.id + " 的产出表，可以像普通表格一样继续改；异常行在任务详情里单独看";
        // 表体被整份换掉，上一版的撤销快照就对不上了，先关掉
        record.undoAvailable = false;
        record.undoRowCount = 0;
        // 每次处理只留一条「什么时候处理完成」：过程细节在任务详情的日志里看，
        // 仓库这边不需要每跑一次就灌进十几条中间步骤，找起来反而费劲
        record.logs.removeIf(entry -> "任务处理".equals(entry.action)
                || "归档".equals(entry.action) || "重新归档".equals(entry.action)
                || "处理完成".equals(entry.action) || "重新处理完成".equals(entry.action));
        String when = task.finishedAt == null || task.finishedAt.isBlank() ? TimeUtils.nowSecond() : task.finishedAt;
        String detail = "任务 " + task.id + " 处理完成，输出 " + table.rows.size() + " 行"
                + (task.anomalyRows > 0 ? "，异常隔离 " + task.anomalyRows + " 行" : "");
        if (exists) {
            record.log(when, "重新处理完成", "任务重跑，已按新结果覆盖本表；" + detail, "ok");
        } else {
            record.log(when, "处理完成", detail, "ok");
        }
        save(record, table);
        return record.summary();
    }

    // 产出里优先收 xlsx：xls 是给 HR 导入的老格式，xlsx 才是能继续编辑的那一份。
    private Path outputWorkbookOf(TaskRecord task) {
        Path dir = paths.taskOutputDir(task.id);
        Path fallback = null;
        for (TaskRecord.Artifact artifact : task.artifacts) {
            if (artifact == null || artifact.name == null) {
                continue;
            }
            Path file = dir.resolve(artifact.name).normalize();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            if (artifact.name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                return file;
            }
            if (fallback == null) {
                fallback = file;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalArgumentException("任务没有产出可归档的表格文件，看看任务详情里的日志");
    }

    // 一句话建表或多表融合。sheetIds 为空时按 append 合并所有表，否则按用户点选的范围。
    public Map<String, Object> compose(String instruction, List<String> sheetIds) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("先说一句要做什么表");
        }
        List<SheetRecord> scope = resolveScope(sheetIds);
        List<Map<String, Object>> briefs = new ArrayList<>();
        for (SheetRecord record : scope) {
            Table table = readTable(record);
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("id", record.id);
            brief.put("name", record.name);
            brief.put("columns", table.columns);
            brief.put("row_count", table.rows.size());
            brief.put("sample", sampleRows(table, 2));
            briefs.add(brief);
        }

        Map<String, Object> plan = agent.compose(instruction, briefs);
        String mode = String.valueOf(plan.getOrDefault("mode", "create"));
        if ("merge".equals(mode)) {
            if (scope.size() < 2) {
                throw new IllegalArgumentException("至少要选两张表才能融合");
            }
            return merge(scope, plan, instruction);
        }
        if ("edit".equals(mode)) {
            throw new IllegalArgumentException("要改已有表的话，在表格下方的「一句话改表」里说更准");
        }
        return create(plan, instruction);
    }

    private Map<String, Object> create(Map<String, Object> plan, String instruction) {
        List<String> columns = toStrings(plan.get("columns"));
        if (columns.size() < 2) {
            throw new IllegalArgumentException("没听出要哪些列，可以说「新建一张表，列：工号、姓名、部门」");
        }
        List<String> cleaned = new ArrayList<>();
        for (String column : columns) {
            String name = column == null ? "" : column.trim();
            if (!name.isEmpty() && !cleaned.contains(name)) {
                cleaned.add(name);
            }
        }
        Table table = new Table(cleaned, new ArrayList<>());
        for (List<String> row : toRows(plan.get("sample_rows"))) {
            List<String> cells = new ArrayList<>(cleaned.size());
            for (int i = 0; i < cleaned.size(); i++) {
                cells.add(i < row.size() ? row.get(i) : "");
            }
            table.rows.add(cells);
        }
        String name = plan.get("name") == null ? "新建表格" : String.valueOf(plan.get("name")).trim();
        SheetRecord record = newRecord(name.isEmpty() ? "新建表格" : name, "nl");
        record.note = instruction;
        record.log("一句话建表", String.valueOf(plan.getOrDefault("summary", instruction)), "ok");
        save(record, table);
        return detail(record.id);
    }

    private Map<String, Object> merge(List<SheetRecord> scope, Map<String, Object> plan, String instruction) {
        String how = String.valueOf(plan.getOrDefault("how", "append"));
        String key = plan.get("key") == null ? "" : String.valueOf(plan.get("key")).trim();
        List<Table> tables = new ArrayList<>();
        for (SheetRecord record : scope) {
            tables.add(readTable(record));
        }
        Table merged = "join".equals(how) && !key.isEmpty() ? joinTables(tables, key) : appendTables(tables);
        List<String> wanted = toStrings(plan.get("columns"));
        if (!wanted.isEmpty()) {
            List<String> keep = new ArrayList<>();
            for (String column : wanted) {
                if (merged.columns.contains(column) && !keep.contains(column)) {
                    keep.add(column);
                }
            }
            if (!keep.isEmpty()) {
                Table trimmed = new Table(keep, new ArrayList<>());
                for (List<String> row : merged.rows) {
                    List<String> cells = new ArrayList<>();
                    for (String column : keep) {
                        int index = merged.columnIndex(column);
                        cells.add(index < 0 || index >= row.size() ? "" : row.get(index));
                    }
                    trimmed.rows.add(cells);
                }
                merged = trimmed;
            }
        }
        String name = plan.get("name") == null ? "融合结果" : String.valueOf(plan.get("name")).trim();
        SheetRecord record = newRecord(name.isEmpty() ? "融合结果" : name, "merge");
        record.note = instruction;
        record.log("多表融合",
                ("join".equals(how) && !key.isEmpty() ? "按「" + key + "」关联 " : "纵向合并 ")
                        + scope.size() + " 张表，得到 " + merged.rows.size() + " 行", "ok");
        save(record, merged);
        return detail(record.id);
    }

    private Table appendTables(List<Table> tables) {
        Set<String> columns = new LinkedHashSet<>();
        for (Table table : tables) {
            columns.addAll(table.columns);
        }
        Table out = new Table(new ArrayList<>(columns), new ArrayList<>());
        for (Table table : tables) {
            for (List<String> source : table.rows) {
                List<String> cells = new ArrayList<>();
                boolean filled = false;
                for (String column : out.columns) {
                    int index = table.columnIndex(column);
                    String value = index < 0 || index >= source.size() ? "" : source.get(index);
                    cells.add(value);
                    filled = filled || !value.isBlank();
                }
                // 源表里留着没填的空行不参与融合
                if (filled) {
                    out.rows.add(cells);
                }
            }
        }
        return out;
    }

    private Table joinTables(List<Table> tables, String key) {
        Table base = tables.get(0);
        if (base.columnIndex(key) < 0) {
            throw new IllegalArgumentException("第一张表里没有「" + key + "」，没法按它关联");
        }
        List<String> columns = new ArrayList<>(base.columns);
        for (int i = 1; i < tables.size(); i++) {
            for (String column : tables.get(i).columns) {
                if (!columns.contains(column)) {
                    columns.add(column);
                }
            }
        }
        Table out = new Table(columns, new ArrayList<>());
        int baseKey = base.columnIndex(key);
        for (int r = 0; r < base.rows.size(); r++) {
            List<String> row = new ArrayList<>();
            for (String column : columns) {
                row.add(base.cell(r, base.columnIndex(column)));
            }
            String keyValue = base.cell(r, baseKey).trim();
            if (keyValue.isEmpty()) {
                // 空行没有关联键，拼进来只会多一行全空
                continue;
            }
            for (int i = 1; i < tables.size(); i++) {
                Table other = tables.get(i);
                int otherKey = other.columnIndex(key);
                if (otherKey < 0) {
                    continue;
                }
                for (int or = 0; or < other.rows.size(); or++) {
                    if (!keyValue.equals(other.cell(or, otherKey).trim())) {
                        continue;
                    }
                    for (int c = 0; c < columns.size(); c++) {
                        if (!row.get(c).isEmpty()) {
                            continue;
                        }
                        int index = other.columnIndex(columns.get(c));
                        if (index >= 0) {
                            row.set(c, other.cell(or, index));
                        }
                    }
                    break;
                }
            }
            out.rows.add(row);
        }
        return out;
    }

    // ==================== 修改 ====================

    public Map<String, Object> revise(String id, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("说一下要怎么改");
        }
        SheetRecord record = store.require(id);
        Table table = readTable(record);
        Map<String, Object> parsed = agent.parseEdit(instruction, table.columns, sampleRows(table, 6));
        List<Map<String, Object>> ops = toOps(parsed.get("ops"));
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("这句话没听出要改什么");
        }
        // 先在副本上算，确认真有变化再备份原表，避免留下一个“撤销了也没区别”的快照
        Table original = table.copy();
        int changed = applyOps(table, ops);
        if (changed <= 0) {
            throw new IllegalArgumentException("按这句话算下来没有需要改的行");
        }
        backup(record, original);
        String summary = String.valueOf(parsed.getOrDefault("summary", instruction));
        record.revisionCount += 1;
        record.lastSummary = summary;
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("summary", summary);
        revision.put("instruction", instruction);
        revision.put("mode", parsed.getOrDefault("mode", "rules"));
        revision.put("changed_rows", changed);
        revision.put("time", TimeUtils.nowSecond());
        record.revisions.add(0, revision);
        while (record.revisions.size() > MAX_REVISIONS) {
            record.revisions.remove(record.revisions.size() - 1);
        }
        record.log("改表", summary + "（影响 " + changed + " 行）", "ok");
        save(record, table);
        Map<String, Object> out = detail(id);
        out.put("revision", revision);
        return out;
    }

    public Map<String, Object> setCell(String id, int row, String column, String value) {
        SheetRecord record = store.require(id);
        Table table = readTable(record);
        int rowIndex = row - 1;
        int columnIndex = table.columnIndex(column);
        if (rowIndex < 0 || rowIndex >= table.rows.size()) {
            throw new IllegalArgumentException("第 " + row + " 行不存在");
        }
        if (columnIndex < 0) {
            throw new IllegalArgumentException("没有「" + column + "」这一列");
        }
        String old = table.cell(rowIndex, columnIndex);
        String next = value == null ? "" : value;
        if (old.equals(next)) {
            return detail(id);
        }
        // 快照必须在改之前落盘，撤销才是回到改之前的样子
        backup(record, table);
        table.setCell(rowIndex, columnIndex, next);
        record.log("改单元格", "第 " + row + " 行「" + column + "」：" + old + " → " + next, "ok");
        save(record, table);
        return detail(id);
    }

    public Map<String, Object> addRow(String id) {
        SheetRecord record = store.require(id);
        Table table = readTable(record);
        List<String> row = new ArrayList<>();
        for (int i = 0; i < table.columns.size(); i++) {
            row.add("");
        }
        // 空行不会写进 xlsx，所以先备份、再补行，撤销时按 undoRowCount 还原
        backup(record, table);
        table.rows.add(row);
        record.log("加行", "在末尾新增第 " + table.rows.size() + " 行", "ok");
        save(record, table);
        return detail(id);
    }

    public Map<String, Object> deleteRow(String id, int row) {
        SheetRecord record = store.require(id);
        Table table = readTable(record);
        int index = row - 1;
        if (index < 0 || index >= table.rows.size()) {
            throw new IllegalArgumentException("第 " + row + " 行不存在");
        }
        backup(record, table);
        table.rows.remove(index);
        record.log("删行", "删除第 " + row + " 行", "warn");
        save(record, table);
        return detail(id);
    }

    public Map<String, Object> addColumn(String id, String name, String value) {
        String column = name == null ? "" : name.trim();
        if (column.isEmpty()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        SheetRecord record = store.require(id);
        Table table = readTable(record);
        if (table.columnIndex(column) >= 0) {
            throw new IllegalArgumentException("已经有「" + column + "」这一列");
        }
        backup(record, table);
        table.columns.add(column);
        for (List<String> row : table.rows) {
            while (row.size() < table.columns.size() - 1) {
                row.add("");
            }
            row.add(value == null ? "" : value);
        }
        record.log("加列", "新增列「" + column + "」", "ok");
        save(record, table);
        return detail(id);
    }

    public Map<String, Object> rename(String id, String name) {
        String title = name == null ? "" : name.trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        SheetRecord record = store.require(id);
        String old = record.name;
        if (old.equals(title)) {
            return detail(id);
        }
        record.name = uniqueName(title, id);
        record.updatedAt = TimeUtils.nowSecond();
        record.log("重命名", old + " → " + record.name, "info");
        store.put(record);
        return detail(id);
    }

    public Map<String, Object> undo(String id) {
        SheetRecord record = store.require(id);
        Path backup = paths.sheetBackupPath(record.id);
        if (!record.undoAvailable || !Files.isRegularFile(backup)) {
            throw new IllegalArgumentException("没有可以撤销的修改");
        }
        try {
            Files.move(backup, paths.sheetPath(record.id), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("撤销失败：" + e.getMessage(), e);
        }
        record.undoAvailable = false;
        record.revisionCount = Math.max(0, record.revisionCount - 1);
        if (!record.revisions.isEmpty()) {
            Map<String, Object> removed = record.revisions.remove(0);
            record.lastSummary = record.revisions.isEmpty() ? "" : String.valueOf(record.revisions.get(0).get("summary"));
            record.log("撤销", "撤回：" + removed.get("summary"), "warn");
        } else {
            // 改单元格、加行这类单步操作没有 revision，用上一条日志说明撤销了什么
            String last = record.logs.isEmpty() ? "" : record.logs.get(0).action + "（" + record.logs.get(0).detail + "）";
            record.log("撤销", last.isEmpty() ? "撤回上一步修改" : "撤回 " + last, "warn");
        }
        // 快照是按修改前的行数存的，先还原行数，再按它把空行补回来
        record.rowCount = Math.max(0, record.undoRowCount);
        Table table = readTable(record);
        record.columns = table.columns;
        record.rowCount = table.rows.size();
        record.updatedAt = TimeUtils.nowSecond();
        cache.remove(record.id);
        store.put(record);
        return detail(id);
    }

    public void delete(String id) {
        SheetRecord record = store.require(id);
        // 删表前把这张表的操作记录整体转存，再补一条「删除表格」：
        // 表没了，但日志里还能看到什么时候删的、删掉的是哪张表、原来有多少行
        int columns = record.columns == null ? 0 : record.columns.size();
        String detail = String.format("已从仓库删除，原有 %d 行、%d 列", record.rowCount, columns);
        store.archiveSheet(record.id, record.name, record.logs,
                TimeUtils.nowSecond(), "删除表格", detail, "warn");
        store.remove(record.id);
        cache.remove(id);
        log.info("删除表格 {}（{}）", record.id, record.name);
    }

    // 批量删除：勾选多张表后一次删掉，每张都走同一套「先归档日志再删文件」的流程。
    public Map<String, Object> deleteMany(List<String> ids) {
        List<String> deleted = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                try {
                    delete(id);
                    deleted.add(id);
                } catch (IllegalArgumentException e) {
                    missing.add(id);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted.size());
        out.put("ids", deleted);
        out.put("missing", missing);
        return out;
    }

    // 「删除所有表格数据」：整库清空表格仓库。
    public Map<String, Object> clearAll() {
        int cleared = store.clearAll();
        cache.clear();
        log.warn("已清空表格仓库：{} 张表、日志和表格文件全部删除", cleared);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cleared", cleared);
        return out;
    }

    // ==================== 操作执行 ====================

    private int applyOps(Table table, List<Map<String, Object>> ops) {
        int changed = 0;
        for (Map<String, Object> op : ops) {
            String type = String.valueOf(op.get("op"));
            switch (type) {
                case "set" -> changed += applySet(table, op);
                case "math" -> changed += applyMath(table, op);
                case "delete" -> changed += applyDelete(table, op);
                case "sort" -> {
                    applySort(table, op);
                    changed = Math.max(changed, table.rows.size());
                }
                case "add_column" -> {
                    applyAddColumn(table, op);
                    changed = Math.max(changed, table.rows.size());
                }
                case "drop_column" -> applyDropColumn(table, op);
                default -> {
                    // 不认识的操作直接跳过，不影响已识别的那部分
                }
            }
        }
        return changed;
    }

    private int applySet(Table table, Map<String, Object> op) {
        int index = table.columnIndex(String.valueOf(op.get("column")));
        if (index < 0) {
            return 0;
        }
        String value = op.get("value") == null ? "" : String.valueOf(op.get("value"));
        int changed = 0;
        for (int rowIndex : targetsOf(table, op.get("scope"))) {
            table.setCell(rowIndex, index, value);
            changed++;
        }
        return changed;
    }

    private int applyMath(Table table, Map<String, Object> op) {
        int index = table.columnIndex(String.valueOf(op.get("column")));
        if (index < 0) {
            return 0;
        }
        String operator = String.valueOf(op.getOrDefault("operator", "+"));
        double delta = toNumber(op.get("value"));
        int changed = 0;
        for (int rowIndex : targetsOf(table, op.get("scope"))) {
            double current = TextUtils.numberOrZero(table.cell(rowIndex, index));
            double result = switch (operator) {
                case "-" -> current - delta;
                case "*" -> current * delta;
                case "/" -> delta == 0 ? current : current / delta;
                default -> current + delta;
            };
            table.setCell(rowIndex, index, TextUtils.numberText(result));
            changed++;
        }
        return changed;
    }

    private int applyDelete(Table table, Map<String, Object> op) {
        List<Integer> targets = new ArrayList<>(targetsOf(table, op.get("scope")));
        targets.sort(Comparator.reverseOrder());
        int changed = 0;
        for (int index : targets) {
            if (index >= 0 && index < table.rows.size()) {
                table.rows.remove(index);
                changed++;
            }
        }
        return changed;
    }

    private void applySort(Table table, Map<String, Object> op) {
        int index = table.columnIndex(String.valueOf(op.get("column")));
        if (index < 0) {
            return;
        }
        boolean desc = "desc".equals(String.valueOf(op.get("order")));
        Comparator<List<String>> comparator = (a, b) -> {
            String left = index < a.size() ? a.get(index) : "";
            String right = index < b.size() ? b.get(index) : "";
            Double ln = TextUtils.parseNumber(left);
            Double rn = TextUtils.parseNumber(right);
            if (ln != null && rn != null) {
                return Double.compare(ln, rn);
            }
            return left.compareTo(right);
        };
        table.rows.sort(desc ? comparator.reversed() : comparator);
    }

    private void applyAddColumn(Table table, Map<String, Object> op) {
        String column = String.valueOf(op.get("column")).trim();
        if (column.isEmpty() || table.columnIndex(column) >= 0) {
            return;
        }
        String value = op.get("value") == null ? "" : String.valueOf(op.get("value"));
        table.columns.add(column);
        for (List<String> row : table.rows) {
            while (row.size() < table.columns.size() - 1) {
                row.add("");
            }
            row.add(value);
        }
    }

    private void applyDropColumn(Table table, Map<String, Object> op) {
        int index = table.columnIndex(String.valueOf(op.get("column")));
        if (index < 0 || table.columns.size() <= 1) {
            return;
        }
        table.columns.remove(index);
        for (List<String> row : table.rows) {
            if (index < row.size()) {
                row.remove(index);
            }
        }
    }

    private List<Integer> targetsOf(Table table, Object scope) {
        List<Integer> out = new ArrayList<>();
        if (!(scope instanceof Map<?, ?> map)) {
            return out;
        }
        String type = String.valueOf(map.get("type"));
        if ("row".equals(type)) {
            int row = (int) toNumber(map.get("row"));
            int index = row - 1;
            if (index >= 0 && index < table.rows.size()) {
                out.add(index);
            }
            return out;
        }
        if ("where".equals(type)) {
            List<Map<String, Object>> conditions = toOps(map.get("conditions"));
            for (int i = 0; i < table.rows.size(); i++) {
                boolean all = true;
                for (Map<String, Object> condition : conditions) {
                    if (!matchesCondition(table, i, condition)) {
                        all = false;
                        break;
                    }
                }
                if (all && !conditions.isEmpty()) {
                    out.add(i);
                }
            }
            return out;
        }
        for (int i = 0; i < table.rows.size(); i++) {
            out.add(i);
        }
        return out;
    }

    private boolean matchesCondition(Table table, int rowIndex, Map<String, Object> condition) {
        int index = table.columnIndex(String.valueOf(condition.get("column")));
        if (index < 0) {
            return false;
        }
        String cell = table.cell(rowIndex, index).trim();
        if (Boolean.TRUE.equals(condition.get("empty"))) {
            return cell.isEmpty();
        }
        String expected = condition.get("equals") == null ? null : String.valueOf(condition.get("equals"));
        if (expected != null) {
            return cell.equalsIgnoreCase(expected.trim());
        }
        String contains = condition.get("contains") == null ? null : String.valueOf(condition.get("contains"));
        if (contains != null) {
            return cell.toLowerCase(Locale.ROOT).contains(contains.trim().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    // ==================== 存储与缓存 ====================

    private SheetRecord newRecord(String name, String origin) {
        SheetRecord record = new SheetRecord();
        record.id = store.nextId();
        record.name = uniqueName(name, null);
        record.origin = origin;
        record.createdAt = TimeUtils.nowSecond();
        record.updatedAt = record.createdAt;
        return record;
    }

    // 同名表格容易在「一句话指定表」时打架，重名就自动加编号。
    private String uniqueName(String name, String exceptId) {
        String base = name == null || name.isBlank() ? "表格" : name.trim();
        Set<String> used = new HashSet<>();
        for (SheetRecord record : store.all()) {
            if (exceptId != null && exceptId.equals(record.id)) continue;
            used.add(record.name);
        }
        if (!used.contains(base)) {
            return base;
        }
        int index = 2;
        while (used.contains(base + " (" + index + ")")) {
            index++;
        }
        return base + " (" + index + ")";
    }

    private void save(SheetRecord record, Table table) {
        try {
            SheetFile.write(paths.sheetPath(record.id), table);
        } catch (IOException e) {
            throw new IllegalStateException("表格保存失败：" + e.getMessage(), e);
        }
        record.columns = table.columns;
        record.rowCount = table.rows.size();
        record.updatedAt = TimeUtils.nowSecond();
        cache.remove(record.id);
        store.put(record);
    }

    // 修改前留一份快照。快照按补齐后的表写，空行虽然不写单元格，
    private void backup(SheetRecord record, Table table) {
        Path source = paths.sheetPath(record.id);
        if (!Files.isRegularFile(source)) {
            record.undoAvailable = false;
            return;
        }
        try {
            SheetFile.write(paths.sheetBackupPath(record.id), table);
            record.undoAvailable = true;
            record.undoRowCount = table.rows.size();
        } catch (IOException e) {
            log.warn("备份表格失败，撤销将不可用：{}", e.getMessage());
            record.undoAvailable = false;
        }
    }

    private Table readTable(SheetRecord record) {
        try {
            Table table = SheetFile.read(paths.sheetPath(record.id));
            // 老版本把合并标题当成表头，索引里记的列名是一排“列2…列18”；现在按内容重新认出表头后，
            // 顺手把列名和行数改回来，用户不用重新上传。
            if (hasPlaceholderColumns(record.columns) && !hasPlaceholderColumns(table.columns)) {
                record.columns = table.columns;
                record.rowCount = table.rows.size();
                record.updatedAt = TimeUtils.nowSecond();
                record.log("修复表头", "重新识别表头，列名已更正为 " + table.columns.size() + " 列", "ok");
                store.put(record);
                cache.remove(record.id);
                return table;
            }
            // 用户加的空行在 Excel 里没有内容，读回来会被当成空行丢掉；
            // 用索引里记下的行数补齐，保证“加一行再填”这个动作不会白做。
            while (table.rows.size() < record.rowCount) {
                List<String> blank = new ArrayList<>();
                for (int i = 0; i < table.columns.size(); i++) {
                    blank.add("");
                }
                table.rows.add(blank);
            }
            return table;
        } catch (IOException e) {
            throw new IllegalStateException("读取表格失败：" + e.getMessage(), e);
        }
    }

    private boolean hasPlaceholderColumns(List<String> columns) {
        if (columns == null) {
            return false;
        }
        int count = 0;
        for (String column : columns) {
            if (column != null && column.matches("列\\d+")) {
                count++;
            }
        }
        return count >= 2;
    }

    private Table cachedTable(SheetRecord record) {
        Path path = paths.sheetPath(record.id);
        String stamp;
        try {
            stamp = Files.isRegularFile(path)
                    ? Files.getLastModifiedTime(path).toMillis() + "|" + Files.size(path)
                    : "missing";
        } catch (IOException e) {
            stamp = "missing";
        }
        synchronized (cache) {
            Cached hit = cache.get(record.id);
            if (hit != null && hit.stamp.equals(stamp)) {
                return hit.table;
            }
        }
        Table table = readTable(record);
        Cached cached = new Cached();
        cached.stamp = stamp;
        cached.table = table;
        synchronized (cache) {
            cache.put(record.id, cached);
        }
        return table;
    }

    private Table readUpload(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(in);
            try (workbook) {
                return SheetFile.read(workbook);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("「" + file.getOriginalFilename() + "」读不出来，请确认是 Excel 文件");
        }
    }

    private List<SheetRecord> resolveScope(List<String> sheetIds) {
        if (sheetIds == null || sheetIds.isEmpty()) {
            return store.all();
        }
        List<SheetRecord> out = new ArrayList<>();
        for (String id : sheetIds) {
            if (id != null && !id.isBlank()) {
                out.add(store.require(id));
            }
        }
        return out;
    }

    private List<Map<String, Object>> sampleRows(Table table, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int r = 0; r < Math.min(limit, table.rows.size()); r++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < table.columns.size(); c++) {
                row.put(table.columns.get(c), table.cell(r, c));
            }
            out.add(row);
        }
        return out;
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
        return columns.indexOf(column);
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

    private double toNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        Double parsed = TextUtils.parseNumber(value == null ? null : String.valueOf(value));
        return parsed == null ? 0d : parsed;
    }

    private List<String> toStrings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private List<List<String>> toRows(Object value) {
        List<List<String>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof List<?> row) {
                    List<String> cells = new ArrayList<>();
                    for (Object cell : row) {
                        cells.add(cell == null ? "" : String.valueOf(cell));
                    }
                    out.add(cells);
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> toOps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> op = new LinkedHashMap<>();
                    map.forEach((k, v) -> op.put(String.valueOf(k), v));
                    out.add(op);
                }
            }
        }
        return out;
    }

    private static class Cached {
        private String stamp = "";
        private Table table = new Table();
    }
}
