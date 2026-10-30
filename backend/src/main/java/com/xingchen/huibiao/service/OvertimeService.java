package com.xingchen.huibiao.service;

import com.xingchen.huibiao.dto.ApiRequests.FileRef;
import com.xingchen.huibiao.dto.ApiRequests.MappingEntry;
import com.xingchen.huibiao.dto.ApiRequests.TaskConfig;
import com.xingchen.huibiao.model.Anomaly;
import com.xingchen.huibiao.model.FileMeta;
import com.xingchen.huibiao.model.IntentResult;
import com.xingchen.huibiao.model.ProcessResult;
import com.xingchen.huibiao.util.TextUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 加班记录筛选。ERP 导出的表有 3.6 万行，先按部门和日期过滤，再逐行校验时长。
@Service
public class OvertimeService {

    private final FileService files;
    private final HeaderCatalog catalog;
    private final SettingsService settings;
    private final TemplateResolver resolver;

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d/yy", Locale.US);

    public OvertimeService(FileService files, HeaderCatalog catalog, SettingsService settings,
                           TemplateResolver resolver) {
        this.files = files;
        this.catalog = catalog;
        this.settings = settings;
        this.resolver = resolver;
    }

    public ProcessResult process(List<FileRef> refs, IntentResult plan, TaskConfig config) {
        FileMeta meta = pick(refs, plan);
        if (meta == null) {
            throw new IllegalStateException("没有找到加班表，请把 ERP 导出的表角色设为“ERP 加班表”");
        }
        ExcelService.SheetData sheet = files.readSheet(meta);
        Map<String, String> fields = catalog.suggest(sheet.headers(), settings.mergedAliases());

        String empCol = mapped(config, "emp_no", meta.fileId, fields.get("emp_no"));
        String nameCol = mapped(config, "name", meta.fileId, fields.get("name"));
        String deptCol = mapped(config, "department", meta.fileId, fields.get("department"));
        String positionCol = mapped(config, "position", meta.fileId, fields.get("position"));
        String dateCol = mapped(config, "date", meta.fileId, fields.get("start_date"));
        String hoursCol = mapped(config, "overtime_hours", meta.fileId, fields.get("overtime_hours"));

        boolean strictDept = settings.boolRule("strict_department", false);
        LocalDate start = TextUtils.parseDate(plan.startDate);
        LocalDate end = TextUtils.parseDate(plan.endDate);

        ProcessResult result = new ProcessResult();
        result.outputColumns = plan.targetTemplate.columns();
        result.totalRows = sheet.rows().size();
        result.rules = new LinkedHashMap<>();
        result.rules.put("strict_department", strictDept);
        result.rules.put("date_style", settings.textRule("date_style", "M/D/YY"));
        result.rules.put("anomaly_threshold", settings.numberRule("anomaly_threshold", 20));
        ColumnExpressions expressions = ColumnExpressions.of(config);
        checkTemplate(plan, config, result, empCol, nameCol, deptCol, positionCol, dateCol, hoursCol, expressions);

        int candidateRows = 0;
        double totalHours = 0;
        int missingHours = 0;
        int garbled = 0;

        for (ExcelService.RowData row : sheet.rows()) {
            String dept = value(row, deptCol);
            if (plan.department != null && deptCol != null && !deptMatches(dept, plan.department, strictDept)) {
                continue;
            }
            String rawDate = value(row, dateCol);
            LocalDate date = TextUtils.parseDate(rawDate);
            if (start != null && (date == null || date.isBefore(start))) {
                continue;
            }
            if (end != null && (date == null || date.isAfter(end))) {
                continue;
            }
            candidateRows += 1;

            if (TextUtils.garbled(row.raw())) {
                garbled += 1;
                result.anomalies.add(new Anomaly(row.rowNumber(), meta.name,
                        "单元格里有无法识别的字符，疑似编码异常，整行隔离", row.raw(), "编码异常"));
                continue;
            }
            Double hours = TextUtils.parseNumber(value(row, hoursCol));
            if (hours == null) {
                missingHours += 1;
                result.anomalies.add(new Anomaly(row.rowNumber(), meta.name,
                        "申请加班为空或不是数字，整行隔离", row.raw(), "时长异常"));
                continue;
            }
            if (hours < 0) {
                result.anomalies.add(new Anomaly(row.rowNumber(), meta.name,
                        "申请加班为负数，整行隔离", row.raw(), "时长异常"));
                continue;
            }
            if (date == null) {
                result.anomalies.add(new Anomaly(row.rowNumber(), meta.name,
                        "起始日期无法解析，整行隔离", row.raw(), "日期异常"));
                continue;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            for (String column : result.outputColumns) {
                String expr = expressions.exprOf(column);
                if (expr != null) {
                    String rendered = ColumnExpressions.render(expr, row.values());
                    out.put(column, rendered == null ? "" : rendered);
                    continue;
                }
                TemplateResolver.Resolved resolved = resolver.resolve(column, "overtime");
                String key = resolved == null ? "" : resolved.key();
                Object cell = switch (key) {
                    case "emp_no" -> value(row, empCol);
                    case "name" -> value(row, nameCol);
                    case "department" -> value(row, deptCol);
                    case "position" -> value(row, positionCol);
                    case "date" -> date.format(SHORT_DATE);
                    case "overtime_hours" -> numberValue(hours);
                    // 模板里带“免打卡”时和请假结算保持一致，直接写固定值“是”
                    case "no_clock" -> "是";
                    default -> "";
                };
                out.put(column, cell);
            }
            result.addRow(out);
            totalHours += hours;
        }

        result.stats.put("source_rows", sheet.rows().size());
        result.stats.put("candidate_rows", candidateRows);
        result.stats.put("output_rows", result.rows.size());
        result.stats.put("total_hours", TextUtils.round1(totalHours));
        result.stats.put("skipped_rows", sheet.rows().size() - candidateRows);

        boolean isolate = config == null || config.isolateAnomaly() == null || config.isolateAnomaly();
        if (plan.department != null || start != null || end != null) {
            result.warnings.add("按条件筛选后保留 " + candidateRows + " 行，源表共 " + sheet.rows().size() + " 行。");
        }
        if (garbled > 0) {
            result.warnings.add("有 " + garbled + " 行包含无法识别的字符（疑似编码异常），"
                    + (isolate ? "已经整行隔离并写入异常日志。" : "这些行已跳过。"));
        }
        if (missingHours > 0) {
            result.warnings.add("有 " + missingHours + " 行申请加班为空或不是数字，"
                    + (isolate ? "已经隔离到异常日志。" : "这些行已跳过。"));
        }
        result.warnings.add("日期按 HR 老系统的 M/D/YY 输出，例如 4/1/26。");

        if (!isolate) {
            int hidden = result.anomalies.size();
            result.anomalies.clear();
            if (hidden > 0) {
                result.warnings.add("本次关闭了脏数据隔离：" + hidden + " 条问题数据没有写入异常日志。");
            }
        }
        result.stats.put("anomaly_rows", result.anomalies.size());
        result.finish();
        return result;
    }

    private void checkTemplate(IntentResult plan, TaskConfig config, ProcessResult result,
                               String empCol, String nameCol, String deptCol, String positionCol,
                               String dateCol, String hoursCol, ColumnExpressions expressions) {
        List<String> unresolved = new ArrayList<>();
        for (String column : resolver.unresolved(result.outputColumns, "overtime")) {
            // 配了拼接表达式的列不算没来源
            if (!expressions.hasExpr(column)) {
                unresolved.add(column);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String column : result.outputColumns) {
            if (expressions.hasExpr(column)) {
                continue;
            }
            TemplateResolver.Resolved resolved = resolver.resolve(column, "overtime");
            if (resolved == null || !resolved.required()) {
                continue;
            }
            boolean ready = switch (resolved.key()) {
                case "emp_no" -> empCol != null;
                case "name" -> nameCol != null;
                case "department" -> deptCol != null;
                case "position" -> positionCol != null;
                case "date" -> dateCol != null;
                case "overtime_hours" -> hoursCol != null;
                default -> true;
            };
            if (!ready) {
                missing.add(column);
            }
        }
        boolean strict = config != null && Boolean.TRUE.equals(config.strictFormat());
        if (strict && !unresolved.isEmpty()) {
            throw new IllegalStateException("严格格式校验未通过：模板列「" + String.join("、", unresolved)
                    + "」在加班桥接里没有对应的数据来源，请改模板或先在映射里指定。");
        }
        if (strict && !missing.isEmpty()) {
            throw new IllegalStateException("严格格式校验未通过：模板列「" + String.join("、", missing)
                    + "」在源表里找不到对应字段，请先补映射。");
        }
        if (!unresolved.isEmpty()) {
            result.warnings.add("模板列「" + String.join("、", unresolved) + "」没有对应的数据来源，导出时是空值。");
        }
        if (!missing.isEmpty()) {
            result.warnings.add("模板列「" + String.join("、", missing) + "」在源表里没找到对应字段，导出时是空值。");
        }
    }

    private Object numberValue(double value) {
        double rounded = TextUtils.round1(value);
        if (Math.abs(rounded - Math.rint(rounded)) < 0.0001) {
            return (long) Math.rint(rounded);
        }
        return rounded;
    }

    // 按内部字段键找映射，模板列叫“工号”还是“员工编号”都能对上。
    private String mapped(TaskConfig config, String key, String fileId, String fallback) {
        if (config == null || config.mapping() == null) {
            return fallback;
        }
        for (MappingEntry entry : config.mapping()) {
            if (TextUtils.isBlank(entry.column())) {
                continue;
            }
            TemplateResolver.Resolved resolved = resolver.resolve(entry.target(), "overtime");
            if (resolved == null || !key.equals(resolved.key())) {
                continue;
            }
            if (TextUtils.isBlank(entry.fileId()) || entry.fileId().equals(fileId)) {
                return entry.column();
            }
        }
        return fallback;
    }

    private FileMeta pick(List<FileRef> refs, IntentResult plan) {
        for (FileRef ref : refs) {
            FileMeta meta = files.require(ref.fileId());
            String role = ref.role() == null || ref.role().isBlank() ? meta.role : ref.role();
            if ("overtime".equals(role)) {
                return meta;
            }
        }
        for (FileRef ref : refs) {
            FileMeta meta = files.require(ref.fileId());
            Map<String, String> fields = catalog.suggest(files.readSheet(meta).headers(), settings.mergedAliases());
            if (fields.containsKey("overtime_hours")) {
                return meta;
            }
        }
        return null;
    }

    private boolean deptMatches(String dept, String filter, boolean strict) {
        String a = TextUtils.norm(dept);
        String b = TextUtils.norm(filter);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (strict) {
            return a.equals(b);
        }
        return a.contains(b) || b.contains(a);
    }

    private String value(ExcelService.RowData row, String column) {
        if (column == null) {
            return "";
        }
        String value = row.values().get(column);
        return value == null ? "" : value.trim();
    }
}
