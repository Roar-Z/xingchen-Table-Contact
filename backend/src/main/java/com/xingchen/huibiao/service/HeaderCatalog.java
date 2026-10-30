package com.xingchen.huibiao.service;

import com.xingchen.huibiao.util.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 表头词典。系统自带的叫法放在这里，用户补充的别名存在数据库的 kv 表里，读出来再合并。
@Component
public class HeaderCatalog {

    public record FieldDef(String field, String label, List<String> aliases) {
    }

    public record Match(String field, String label, int score) {
    }

    private final List<FieldDef> fields = new ArrayList<>();

    public HeaderCatalog() {
        fields.add(new FieldDef("emp_no", "工号", List.of(
                "工号", "员工编号", "人员编号", "员工号", "人员代码", "员工工号", "人员工号", "empno", "employeeid")));
        fields.add(new FieldDef("name", "姓名", List.of(
                "姓名", "申请人", "员工姓名", "人员姓名", "姓 名", "name")));
        fields.add(new FieldDef("department", "部门", List.of(
                "部门", "班组名称", "所属部门", "组织", "车间", "部门名称", "班组")));
        fields.add(new FieldDef("position", "职位", List.of(
                "职位", "岗位", "职位名称", "岗位名称", "职务")));
        fields.add(new FieldDef("leave_type", "请假类型", List.of(
                "请假类型", "假别", "假期类型", "缺勤类别", "请假类别", "类型")));
        fields.add(new FieldDef("start_date", "起始日期", List.of(
                "起始日期", "开始日期", "日期", "加班日期", "请假开始日期")));
        fields.add(new FieldDef("start_time", "起始时间", List.of(
                "起始时间", "开始时间", "上班时间", "请假开始时间")));
        fields.add(new FieldDef("end_date", "结束日期", List.of(
                "结束日期", "截止日期", "终止日期", "请假结束日期")));
        fields.add(new FieldDef("end_time", "结束时间", List.of(
                "结束时间", "下班时间", "终止时间", "请假结束时间")));
        fields.add(new FieldDef("days", "天数", List.of(
                "天", "天数", "请假天数", "合计天数", "休假天数")));
        fields.add(new FieldDef("hours", "小时", List.of(
                "小时", "时长", "请假小时", "合计小时", "小时数")));
        fields.add(new FieldDef("annual_left", "剩余年假", List.of(
                "剩余年假", "年假余额", "可用年假", "剩余年假天数", "年假剩余")));
        fields.add(new FieldDef("overtime_hours", "申请加班", List.of(
                "申请加班", "加班时长", "加班小时", "加班时数")));
        fields.add(new FieldDef("document_no", "单据编号", List.of(
                "单据编号", "流程编号", "单号", "申请单号")));
        fields.add(new FieldDef("reason", "原因", List.of(
                "原因", "请假原因", "事由")));
        fields.add(new FieldDef("request_title", "请求标题", List.of(
                "请求标题", "流程标题", "标题")));
    }

    public List<FieldDef> fields() {
        return fields;
    }

    // 把系统别名和用户补充的别名合成一张表。
    public Map<String, List<String>> mergedAliases(Map<String, List<String>> extras) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (FieldDef def : fields) {
            map.put(def.field(), new ArrayList<>(def.aliases()));
        }
        if (extras != null) {
            extras.forEach((field, list) -> {
                if (map.containsKey(field) && list != null) {
                    for (String alias : list) {
                        if (alias != null && !alias.isBlank() && !map.get(field).contains(alias)) {
                            map.get(field).add(alias);
                        }
                    }
                }
            });
        }
        return map;
    }

    public Match match(String header, Map<String, List<String>> aliases) {
        String h = TextUtils.norm(header);
        if (h.isEmpty()) {
            return null;
        }
        Match best = null;
        for (FieldDef def : fields) {
            List<String> list = aliases.getOrDefault(def.field(), def.aliases());
            for (String alias : list) {
                String a = TextUtils.norm(alias);
                if (a.isEmpty()) {
                    continue;
                }
                int score = 0;
                if (h.equals(a)) {
                    score = 3;
                } else if (h.contains(a) || a.contains(h)) {
                    score = 2;
                }
                if (score > 0 && (best == null || score > best.score())) {
                    best = new Match(def.field(), def.label(), score);
                }
            }
        }
        return best;
    }

    // 给一行候选表头打分。低于 2 个字段的行不作为表头，避免把标题行当成表头。
    public int scoreRow(List<String> row, Map<String, List<String>> aliases) {
        int score = 0;
        int hits = 0;
        for (String cell : row) {
            Match m = match(cell, aliases);
            if (m != null) {
                score += m.score();
                hits += 1;
            }
        }
        return hits >= 2 ? score : 0;
    }

    public Map<String, String> suggest(List<String> headers, Map<String, List<String>> aliases) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String header : headers) {
            Match m = match(header, aliases);
            if (m != null && !result.containsKey(m.field())) {
                result.put(m.field(), header);
            }
        }
        return result;
    }
}
