package com.xingchen.huibiao.service;

import com.xingchen.huibiao.util.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// 输出模板列到系统内部字段的对照表。
@Component
public class TemplateResolver {

    // fromSource：这一列的值来自源表的某一列，映射时需要选列。
    public record Resolved(String key, boolean fromSource, boolean required) {
    }

    private final HeaderCatalog catalog;
    private final SettingsService settings;

    public TemplateResolver(HeaderCatalog catalog, SettingsService settings) {
        this.catalog = catalog;
        this.settings = settings;
    }

    public Resolved resolve(String column, String scene) {
        String n = TextUtils.norm(column);
        if (n.isEmpty()) {
            return null;
        }
        return "overtime".equals(scene) ? resolveOvertime(column, n) : resolveLeave(column, n);
    }

    private Resolved resolveLeave(String column, String n) {
        switch (n) {
            case "免打卡":
                return new Resolved("no_clock", false, false);
            case "缺勤类别", "假期类别", "结算类别", "扣减类别":
                return new Resolved("leave_type", false, false);
            case "合计小时", "请假小时", "结算小时", "扣减小时":
                return new Resolved("hours", false, true);
            case "起始时间", "开始时间", "起始日期时间":
                return new Resolved("start", false, true);
            case "终止时间", "结束时间", "终止日期时间":
                return new Resolved("end", false, true);
            case "单据编号", "原单据编号", "原请假单号":
                return new Resolved("document_no", true, false);
            default:
                break;
        }
        HeaderCatalog.Match match = catalog.match(column, settings.mergedAliases());
        if (match == null) {
            return null;
        }
        return switch (match.field()) {
            case "emp_no" -> new Resolved("emp_no", true, true);
            case "name" -> new Resolved("name", true, true);
            case "department" -> new Resolved("department", true, true);
            case "position" -> new Resolved("position", true, true);
            case "document_no" -> new Resolved("document_no", true, false);
            case "leave_type" -> new Resolved("leave_type", false, false);
            case "start_date", "start_time" -> new Resolved("start", false, true);
            case "end_date", "end_time" -> new Resolved("end", false, true);
            case "days", "hours" -> new Resolved("hours", false, true);
            default -> null;
        };
    }

    private Resolved resolveOvertime(String column, String n) {
        switch (n) {
            // 加班表多半一行一条记录，只要一个日期；HR 模板里常写成“起始日期/起始时间/加班日期”
            case "起始日期", "加班日期", "加班时间", "日期", "考勤日期", "起始时间", "开始时间":
                return new Resolved("date", false, true);
            // 加班时长在 HR 模板里写法很杂：申请加班、合计小时、时长、小时数……
            case "申请加班", "加班时长", "加班小时", "加班时数", "加班合计",
                 "合计小时", "总时长", "加班总时长", "时长", "小时数", "小时":
                return new Resolved("overtime_hours", true, true);
            case "免打卡":
                return new Resolved("no_clock", false, false);
            default:
                break;
        }
        HeaderCatalog.Match match = catalog.match(column, settings.mergedAliases());
        if (match == null) {
            return null;
        }
        return switch (match.field()) {
            case "emp_no" -> new Resolved("emp_no", true, true);
            case "name" -> new Resolved("name", true, true);
            case "department" -> new Resolved("department", true, true);
            case "position" -> new Resolved("position", true, true);
            case "start_date" -> new Resolved("date", false, true);
            case "overtime_hours" -> new Resolved("overtime_hours", true, true);
            default -> null;
        };
    }

    // 找不到数据来源的模板列，按列顺序返回。
    public List<String> unresolved(List<String> columns, String scene) {
        List<String> list = new ArrayList<>();
        for (String column : columns) {
            if (resolve(column, scene) == null) {
                list.add(column);
            }
        }
        return list;
    }
}
