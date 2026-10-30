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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 假期余量结算：把请假明细和假期余额对起来，按 HR 模板的列算出一行行记录
@Service
public class LeaveSettlementService {

    private final FileService files;
    private final HeaderCatalog catalog;
    private final SettingsService settings;
    private final TemplateResolver resolver;

    public LeaveSettlementService(FileService files, HeaderCatalog catalog, SettingsService settings,
                                  TemplateResolver resolver) {
        this.files = files;
        this.catalog = catalog;
        this.settings = settings;
        this.resolver = resolver;
    }

    public ProcessResult settle(List<FileRef> refs, IntentResult plan, TaskConfig config) {
        FileMeta leaveMeta = pick(refs, plan, "leave_request");
        FileMeta annualMeta = pick(refs, plan, "annual_balance");
        if (leaveMeta == null) {
            throw new IllegalStateException("没有找到请假单，请把 OA 导出的请假表角色设为“OA 请假单”");
        }
        ExcelService.SheetData leave = files.readSheet(leaveMeta);
        ExcelService.SheetData annual = annualMeta == null ? null : files.readSheet(annualMeta);

        Map<String, String> leaveFields = catalog.suggest(leave.headers(), settings.mergedAliases());
        Map<String, String> annualFields = annual == null ? new LinkedHashMap<>()
                : catalog.suggest(annual.headers(), settings.mergedAliases());

        String empCol = mapped(config, "emp_no", leaveMeta.fileId, leaveFields.get("emp_no"));
        String nameCol = mapped(config, "name", leaveMeta.fileId, leaveFields.get("name"));
        String deptCol = mapped(config, "department", leaveMeta.fileId, leaveFields.get("department"));
        String posCol = mapped(config, "position", leaveMeta.fileId, leaveFields.get("position"));
        String typeCol = mapped(config, "leave_type", leaveMeta.fileId, leaveFields.get("leave_type"));
        String startDateCol = mapped(config, "start", leaveMeta.fileId, leaveFields.get("start_date"));
        String endDateCol = mapped(config, "end", leaveMeta.fileId, leaveFields.get("end_date"));
        String startTimeCol = leaveFields.get("start_time");
        String endTimeCol = leaveFields.get("end_time");
        String mappedHours = mapped(config, "hours", leaveMeta.fileId, null);
        String daysCol = leaveFields.get("days");
        String hoursCol = leaveFields.get("hours");
        if (mappedHours != null && !mappedHours.equals(daysCol) && !mappedHours.equals(hoursCol)) {
            // 用户手动指到别的列时，就按小时数读
            hoursCol = mappedHours;
        }
        String docCol = mapped(config, "document_no", leaveMeta.fileId, leaveFields.get("document_no"));

        String annualIdCol = annualFields.get("emp_no");
        String annualLeftCol = annualFields.get("annual_left");

        double workday = settings.numberRule("workday_hours", 8);
        boolean annualPriority = settings.boolRule("annual_priority", true);
        boolean strictDept = settings.boolRule("strict_department", false);
        Set<String> deductTypes = new HashSet<>();
        for (String item : settings.textRule("deduct_types", "事假,年假").split("[,，]")) {
            if (!item.isBlank()) {
                deductTypes.add(TextUtils.norm(item));
            }
        }

        Map<String, Double> balances = new HashMap<>();
        int annualRows = 0;
        if (annual != null && annualIdCol != null) {
            for (ExcelService.RowData row : annual.rows()) {
                String id = TextUtils.norm(row.values().get(annualIdCol));
                if (id.isEmpty()) {
                    continue;
                }
                Double left = annualLeftCol == null ? null : TextUtils.parseNumber(row.values().get(annualLeftCol));
                balances.putIfAbsent(id, left == null ? 0d : left);
                annualRows += 1;
            }
        }

        ProcessResult result = new ProcessResult();
        result.outputColumns = plan.targetTemplate.columns();
        result.rules = ruleSnapshot(workday, annualPriority);
        result.totalRows = leave.rows().size();

        // 日期列兜底：映射可能把“起始时间”指到只有时分秒的列，退回真正的日期列，否则整批都会判成日期异常
        startDateCol = preferDateColumn(leave, startDateCol, leaveFields.get("start_date"), result);
        endDateCol = preferDateColumn(leave, endDateCol, leaveFields.get("end_date"), result);

        boolean isolate = isolateOf(config);
        ColumnExpressions expressions = ColumnExpressions.of(config);
        checkTemplate(plan, config, result, empCol, nameCol, deptCol, posCol, startDateCol, endDateCol,
                daysCol, hoursCol, expressions);

        LocalDate filterStart = TextUtils.parseDate(plan.startDate);
        LocalDate filterEnd = TextUtils.parseDate(plan.endDate);
        int candidateRows = 0;
        double annualHours = 0;
        double personalHours = 0;
        int annualOutputRows = 0;
        Set<String> matchedEmployees = new HashSet<>();
        int notFound = 0;
        int missingTime = 0;
        int garbled = 0;
        boolean missingAnnual = annual == null || annualIdCol == null || annualLeftCol == null;

        for (ExcelService.RowData row : leave.rows()) {
            String emp = value(row, empCol);
            String dept = value(row, deptCol);
            String type = value(row, typeCol);
            LocalDate rowDate = TextUtils.parseDate(value(row, startDateCol));

            if (filterStart != null && (rowDate == null || rowDate.isBefore(filterStart))) {
                continue;
            }
            if (filterEnd != null && (rowDate == null || rowDate.isAfter(filterEnd))) {
                continue;
            }
            if (plan.department != null && deptCol != null && !deptMatches(dept, plan.department, strictDept)) {
                continue;
            }
            candidateRows += 1;

            if (TextUtils.garbled(row.raw())) {
                garbled += 1;
                result.anomalies.add(new Anomaly(row.rowNumber(), leaveMeta.name,
                        "单元格里有无法识别的字符，疑似编码异常，整行隔离", row.raw(), "编码异常"));
                continue;
            }
            if (TextUtils.isBlank(emp)) {
                result.anomalies.add(new Anomaly(row.rowNumber(), leaveMeta.name, "工号为空，无法关联年假余额",
                        row.raw(), "工号缺失"));
                continue;
            }
            if (TextUtils.isBlank(type)) {
                type = "事假";
                result.anomalies.add(new Anomaly(row.rowNumber(), leaveMeta.name, "请假类型为空，按事假处理",
                        row.raw(), "字段缺失"));
            }

            String start = TextUtils.combineDateTime(value(row, startDateCol), value(row, startTimeCol));
            String end = TextUtils.combineDateTime(value(row, endDateCol), value(row, endTimeCol));
            if (start == null || end == null) {
                result.anomalies.add(new Anomaly(row.rowNumber(), leaveMeta.name, "起止日期无法解析，整行隔离",
                        row.raw(), "日期异常"));
                continue;
            }
            if (TextUtils.isBlank(value(row, startTimeCol)) || TextUtils.isBlank(value(row, endTimeCol))) {
                missingTime += 1;
            }

            double totalHours = hoursOf(row, daysCol, hoursCol, mappedHours, start, end, workday);
            if (totalHours <= 0) {
                result.anomalies.add(new Anomaly(row.rowNumber(), leaveMeta.name, "请假时长为空或不是数字，整行隔离",
                        row.raw(), "时长异常"));
                continue;
            }

            String id = TextUtils.norm(emp);
            Double leftDays = balances.get(id);
            if (leftDays == null) {
                leftDays = 0d;
                if (!missingAnnual) {
                    notFound += 1;
                    result.anomalies.add(new Anomaly(row.rowNumber(), leaveMeta.name,
                            "工号 " + emp + " 在年假余额表中不存在，按 0 年假处理并输出事假",
                            row.raw(), "年假缺失"));
                }
            } else {
                matchedEmployees.add(id);
            }

            String category = TextUtils.trim(type);
            boolean canDeduct = annualPriority && deductTypes.contains(TextUtils.norm(category)) && !missingAnnual;
            double deduct = canDeduct ? Math.min(totalHours, Math.max(0, leftDays) * workday) : 0;
            deduct = TextUtils.round1(deduct);

            if (deduct > 0) {
                result.addRow(outputRow(result.outputColumns, new RowValues(emp, value(row, nameCol),
                        dept, value(row, posCol), "年假", start, end, deduct, value(row, docCol)),
                        row.values(), expressions));
                annualHours += deduct;
                annualOutputRows += 1;
            }
            double remain = TextUtils.round1(totalHours - deduct);
            if (remain > 0) {
                String outCategory = deduct > 0 ? "事假" : category;
                result.addRow(outputRow(result.outputColumns, new RowValues(emp, value(row, nameCol),
                        dept, value(row, posCol), outCategory, start, end, remain, value(row, docCol)),
                        row.values(), expressions));
                personalHours += remain;
            }
        }

        result.stats.put("source_rows", leave.rows().size());
        result.stats.put("candidate_rows", candidateRows);
        result.stats.put("output_rows", result.rows.size());
        result.stats.put("annual_hours", TextUtils.round1(annualHours));
        result.stats.put("personal_hours", TextUtils.round1(personalHours));
        result.stats.put("annual_rows", annualOutputRows);
        result.stats.put("annual_matched", matchedEmployees.size());

        if (garbled > 0) {
            result.warnings.add("有 " + garbled + " 行包含无法识别的字符（疑似编码异常），"
                    + (isolate ? "已经整行隔离并写入异常日志。" : "这些行已跳过。"));
        }
        if (missingAnnual) {
            result.warnings.add("没有可用的年假余额表，本批全部按原假别输出，没有做年假抵扣。");
        }
        if (notFound > 0) {
            result.warnings.add("有 " + notFound + " 条请假的工号在年假表里找不到，已经按 0 余额输出事假"
                    + (isolate ? "，并记入异常日志。" : "。"));
        }
        if (missingTime > 0) {
            result.warnings.add("有 " + missingTime + " 行没有填具体时间，起止时间按 00:00 补齐，建议复核。");
        }
        result.warnings.add("一条请假拆成年假和事假两行时，起止时间沿用原单据，HR 按合计小时入账。");
        if (!result.rows.isEmpty() && result.rows.size() > candidateRows) {
            result.warnings.add("输出行数 " + result.rows.size() + " 大于候选行数 " + candidateRows
                    + "，差额是年假抵扣拆分产生的。");
        }
        hideAnomalies(result, isolate);
        result.stats.put("anomaly_rows", result.anomalies.size());
        result.finish();
        return result;
    }

    // 关掉脏数据隔离时，问题行照原口径处理（能输出就输出，不能成行的跳过），最后只在 warnings 里提一句
    private void hideAnomalies(ProcessResult result, boolean isolate) {
        if (isolate) {
            return;
        }
        int hidden = result.anomalies.size();
        result.anomalies.clear();
        if (hidden > 0) {
            result.warnings.add("本次关闭了脏数据隔离：" + hidden + " 条问题数据没有写入异常日志。");
        }
    }

    // OA 导出常把“日期”和“时间”拆成两列，映射指错时会取到只有时分秒的那一列。
    private String preferDateColumn(ExcelService.SheetData sheet, String mapped, String fallback,
                                    ProcessResult result) {
        if (mapped != null && hasDateValue(sheet, mapped)) {
            return mapped;
        }
        if (fallback != null && !fallback.equals(mapped) && hasDateValue(sheet, fallback)) {
            result.warnings.add("映射里的「" + mapped + "」只有时分秒，已自动改用源表的「" + fallback + "」作为日期列。");
            return fallback;
        }
        return mapped != null ? mapped : fallback;
    }

    private boolean hasDateValue(ExcelService.SheetData sheet, String column) {
        int limit = Math.min(sheet.rows().size(), 20);
        for (int i = 0; i < limit; i++) {
            if (TextUtils.parseDate(sheet.rows().get(i).values().get(column)) != null) {
                return true;
            }
        }
        return false;
    }

    // 模板列先核对一遍：翻译不了的列、源表里找不到的必填列，严格模式下直接报错，宽松模式记条提醒、照常往下跑
    private void checkTemplate(IntentResult plan, TaskConfig config, ProcessResult result,
                               String empCol, String nameCol, String deptCol, String posCol,
                               String startDateCol, String endDateCol, String daysCol, String hoursCol,
                               ColumnExpressions expressions) {
        List<String> unresolved = new ArrayList<>();
        for (String column : resolver.unresolved(result.outputColumns, "leave")) {
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
            TemplateResolver.Resolved resolved = resolver.resolve(column, "leave");
            if (resolved == null || !resolved.required()) {
                continue;
            }
            boolean ready = switch (resolved.key()) {
                case "emp_no" -> empCol != null;
                case "name" -> nameCol != null;
                case "department" -> deptCol != null;
                case "position" -> posCol != null;
                case "start" -> startDateCol != null;
                case "end" -> endDateCol != null;
                case "hours" -> daysCol != null || hoursCol != null;
                default -> true;
            };
            if (!ready) {
                missing.add(column);
            }
        }
        boolean strict = config != null && Boolean.TRUE.equals(config.strictFormat());
        if (strict && !unresolved.isEmpty()) {
            throw new IllegalStateException("严格格式校验未通过：模板列「" + String.join("、", unresolved)
                    + "」在请假结算里没有对应的数据来源，请改模板或先在映射里指定。");
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

    private Map<String, Object> outputRow(List<String> columns, RowValues values,
                                          Map<String, String> sourceRow, ColumnExpressions expressions) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : columns) {
            // 配了拼接表达式就以表达式为准，源表列不够用时也能把这一格填上
            String expr = expressions.exprOf(column);
            if (expr != null) {
                String rendered = ColumnExpressions.render(expr, sourceRow);
                row.put(column, rendered == null ? "" : rendered);
                continue;
            }
            TemplateResolver.Resolved resolved = resolver.resolve(column, "leave");
            String key = resolved == null ? "" : resolved.key();
            Object cell = switch (key) {
                case "emp_no" -> values.emp();
                case "name" -> values.name();
                case "department" -> values.dept();
                case "position" -> values.position();
                case "leave_type" -> values.category();
                case "start" -> values.start();
                case "end" -> values.end();
                case "hours" -> numberValue(values.hours());
                case "no_clock" -> "是";
                case "document_no" -> values.doc();
                default -> "";
            };
            row.put(column, cell);
        }
        return row;
    }

    private record RowValues(String emp, String name, String dept, String position, String category,
                             String start, String end, double hours, String doc) {
    }

    private Object numberValue(double value) {
        double rounded = TextUtils.round1(value);
        if (Math.abs(rounded - Math.rint(rounded)) < 0.0001) {
            return (long) Math.rint(rounded);
        }
        return rounded;
    }

    private double hoursOf(ExcelService.RowData row, String daysCol, String hoursCol, String preferred,
                           String start, String end, double workday) {
        Double hours = hoursCol == null ? null : TextUtils.parseNumber(value(row, hoursCol));
        Double days = daysCol == null ? null : TextUtils.parseNumber(value(row, daysCol));
        boolean preferDays = preferred != null && preferred.equals(daysCol);

        if (preferDays) {
            if (days != null && days > 0) {
                return TextUtils.round1(days * workday);
            }
            if (hours != null && hours > 0) {
                return TextUtils.round1(hours);
            }
        } else {
            // OA 导出的“小时”列在按天请假时是 0，这时用“天 × 每日工时”
            if (hours != null && hours > 0) {
                return TextUtils.round1(hours);
            }
            if (days != null && days > 0) {
                return TextUtils.round1(days * workday);
            }
        }
        try {
            LocalDateTime s = LocalDateTime.parse(start.replace(' ', 'T'));
            LocalDateTime e = LocalDateTime.parse(end.replace(' ', 'T'));
            long minutes = Duration.between(s, e).toMinutes();
            if (minutes > 0) {
                return TextUtils.round1(minutes / 60d);
            }
        } catch (Exception ignored) {
            // 日期都拼不出来时走下面的 0，交给异常处理
        }
        return 0;
    }

    private boolean isolateOf(TaskConfig config) {
        return config == null || config.isolateAnomaly() == null || config.isolateAnomaly();
    }

    // 按内部字段名找映射。模板列叫“员工编号”还是“工号”都无所谓，先翻译成键再比对。
    private String mapped(TaskConfig config, String key, String fileId, String fallback) {
        if (config == null || config.mapping() == null) {
            return fallback;
        }
        for (MappingEntry entry : config.mapping()) {
            if (TextUtils.isBlank(entry.column())) {
                continue;
            }
            TemplateResolver.Resolved resolved = resolver.resolve(entry.target(), "leave");
            if (resolved == null || !key.equals(resolved.key())) {
                continue;
            }
            if (TextUtils.isBlank(entry.fileId()) || entry.fileId().equals(fileId)) {
                return entry.column();
            }
        }
        return fallback;
    }

    private FileMeta pick(List<FileRef> refs, IntentResult plan, String role) {
        for (FileRef ref : refs) {
            FileMeta meta = files.require(ref.fileId());
            String r = ref.role() == null || ref.role().isBlank() ? meta.role : ref.role();
            if (role.equals(r)) {
                return meta;
            }
        }
        for (FileRef ref : refs) {
            FileMeta meta = files.require(ref.fileId());
            Map<String, String> fields = catalog.suggest(files.readSheet(meta).headers(), settings.mergedAliases());
            if ("annual_balance".equals(role) && fields.containsKey("annual_left")) {
                return meta;
            }
            if ("leave_request".equals(role) && fields.containsKey("leave_type") && !fields.containsKey("annual_left")) {
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

    private Map<String, Object> ruleSnapshot(double workday, boolean annualPriority) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("workday_hours", workday);
        rules.put("annual_priority", annualPriority);
        rules.put("deduct_types", settings.textRule("deduct_types", "事假,年假"));
        rules.put("date_style", settings.textRule("date_style", "M/D/YY"));
        rules.put("anomaly_threshold", settings.numberRule("anomaly_threshold", 20));
        return rules;
    }
}
