package com.xingchen.huibiao.service.sheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参考模板：只给列名和一两行示例，用户照着填或者直接改。
 * 内置几套 HR / 车间常用的，省得每次从空白开始。
 */
public final class SheetTemplates {

    private SheetTemplates() {
    }

    public static List<Map<String, Object>> items() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(item("roster", "员工花名册", "人力",
                List.of("工号", "姓名", "部门", "岗位", "入职日期", "手机号"),
                List.of(
                        List.of("A001", "张明", "总装车间", "装配工", "2024-03-11", "13800000001"),
                        List.of("A002", "李萍", "品质部", "检验员", "2025-07-01", "13800000002"))));
        list.add(item("attendance", "考勤汇总", "人力",
                List.of("工号", "姓名", "部门", "应出勤天数", "实际出勤天数", "缺勤天数", "请假类型", "备注"),
                List.of(
                        List.of("A001", "张明", "总装车间", "22", "21", "1", "事假", ""),
                        List.of("A002", "李萍", "品质部", "22", "22", "0", "", ""))));
        list.add(item("overtime", "加班明细", "车间",
                List.of("工号", "姓名", "部门", "加班日期", "开始时间", "结束时间", "加班时长", "事由"),
                List.of(
                        List.of("A001", "张明", "总装车间", "2026-04-08", "18:00", "21:00", "3", "赶产"),
                        List.of("A002", "李萍", "品质部", "2026-04-09", "18:30", "20:30", "2", "盘点"))));
        list.add(item("expense", "费用报销", "财务",
                List.of("单号", "报销人", "部门", "费用类型", "发生日期", "金额", "发票号", "备注"),
                List.of(
                        List.of("BX202604001", "张明", "总装车间", "差旅", "2026-04-15", "380.50", "04412001", ""),
                        List.of("BX202604002", "李萍", "品质部", "办公用品", "2026-04-16", "126.00", "04412002", ""))));
        list.add(item("shift", "月度排班", "车间",
                List.of("日期", "班次", "工号", "姓名", "车间", "备注"),
                List.of(
                        List.of("2026-04-01", "白班", "A001", "张明", "总装车间", ""),
                        List.of("2026-04-01", "夜班", "A002", "李萍", "总装车间", ""))));
        list.add(item("project", "项目进度", "通用",
                List.of("项目", "负责人", "阶段", "开始日期", "计划完成", "实际完成", "进度", "风险"),
                List.of(
                        List.of("产线数字化", "张明", "实施", "2026-03-01", "2026-06-30", "", "60%", "设备联调待定"),
                        List.of("库存看板", "李萍", "调研", "2026-04-01", "2026-07-31", "", "20%", ""))));
        return list;
    }

    public static Map<String, Object> find(String templateId) {
        return items().stream()
                .filter(item -> templateId != null && templateId.equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("没有这个模板"));
    }

    /**
     * 按一句话里的关键词猜一个模板，例如“做个考勤表”。
     */
    public static Map<String, Object> guess(String instruction) {
        if (instruction == null) {
            return null;
        }
        String text = instruction.toLowerCase();
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("roster", "花名册|员工名单|人员信息|通讯录");
        hints.put("attendance", "考勤|出勤|缺勤|请假");
        hints.put("overtime", "加班");
        hints.put("expense", "报销|费用|发票");
        hints.put("shift", "排班|班次");
        hints.put("project", "项目|进度|里程碑");
        for (Map<String, Object> item : items()) {
            String pattern = hints.get(item.get("id"));
            if (pattern != null && text.matches("(?s).*(" + pattern + ").*")) {
                return item;
            }
        }
        return null;
    }

    private static Map<String, Object> item(String id, String name, String group,
                                            List<String> columns, List<List<String>> sampleRows) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("group", group);
        item.put("columns", columns);
        item.put("sample", sampleRows);
        item.put("desc", String.join(" / ", columns));
        return item;
    }
}
