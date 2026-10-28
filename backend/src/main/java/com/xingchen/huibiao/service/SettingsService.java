package com.xingchen.huibiao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.MappingField;
import com.xingchen.huibiao.model.RuleItem;
import com.xingchen.huibiao.model.TemplateItem;
import com.xingchen.huibiao.util.TextUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 设置文件不大，直接用 JSON 落盘，省掉数据库依赖。
 * 结构：aliases 存用户补充的表头别名，rules 存计算口径，templates 存自定义输出模板。
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private final HeaderCatalog catalog;
    private SettingsData data = new SettingsData();
    private final List<RuleItem> defaults = new ArrayList<>();
    private final List<TemplateItem> builtInTemplates = new ArrayList<>();

    public SettingsService(DataPaths paths, ObjectMapper mapper, HeaderCatalog catalog) {
        this.paths = paths;
        this.mapper = mapper;
        this.catalog = catalog;
        initDefaults();
    }

    private void initDefaults() {
        defaults.add(new RuleItem("workday_hours", "每日折算工时", 8, "number", "小时/天",
                "请假天数换算成小时时使用。ERP 和 HR 的班次时长不同，这里统一按 8 小时。", "计算口径"));
        defaults.add(new RuleItem("annual_priority", "年假优先抵扣", true, "bool", "",
                "请假时先扣剩余年假，超出部分再转成事假。", "计算口径"));
        defaults.add(new RuleItem("deduct_types", "参与抵扣的假别", "事假,年假", "text", "",
                "只有这些假别会消耗年假余额，病假、婚假等原样输出。", "计算口径"));
        defaults.add(new RuleItem("date_style", "日期输出格式", "M/D/YY", "text", "",
                "HR 老系统只认这种短日期，改成 YYYY-MM-DD 会导入失败。", "输出格式"));
        defaults.add(new RuleItem("auto_header", "自动识别表头", true, "bool", "",
                "源表第一行常是标题或合并单元格，开启后自动往下找表头。", "解析"));
        defaults.add(new RuleItem("skip_blank", "跳过完全空行", true, "bool", "",
                "导出文件末尾常带空行，不跳过会被算进异常。", "解析"));
        defaults.add(new RuleItem("strict_department", "部门严格匹配", false, "bool", "",
                "关闭时“精工二”和“精工二车间”视为同一部门。", "解析"));
        defaults.add(new RuleItem("anomaly_threshold", "异常占比上限", 20, "number", "%",
                "异常行占比超过这个比例时，任务标记为待复核并提示先看异常日志，文件仍会生成。", "安全"));

        builtInTemplates.add(new TemplateItem("tpl_absence", "HR 最低版本导入模板（缺勤）", "leave", true,
                "8 列，缺勤类别、起止时间、合计小时、免打卡。",
                List.of("工号", "姓名", "部门", "缺勤类别", "起始时间", "终止时间", "合计小时", "免打卡")));
        builtInTemplates.add(new TemplateItem("tpl_overtime", "HR 加班导入模板", "overtime", true,
                "沿用现有 ERP 表头，日期为 M/D/YY。",
                List.of("工号", "姓名", "部门", "职位", "起始日期", "申请加班")));
        builtInTemplates.add(new TemplateItem("tpl_leave_detail", "年假抵扣结算明细", "leave", true,
                "给财务复核用，多一列原请假单据号。",
                List.of("工号", "姓名", "部门", "缺勤类别", "起始时间", "终止时间", "合计小时", "单据编号")));
    }

    @PostConstruct
    public void load() {
        Path file = paths.settingsFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            SettingsData loaded = mapper.readValue(file.toFile(), SettingsData.class);
            if (loaded.aliases != null) {
                data.aliases = loaded.aliases;
            }
            if (loaded.rules != null) {
                data.rules = loaded.rules;
            }
            if (loaded.templates != null) {
                data.templates = loaded.templates;
            }
        } catch (IOException e) {
            log.warn("设置文件读不出来，先用默认值：{}", e.getMessage());
        }
    }

    public synchronized void save() {
        Path file = paths.settingsFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), data);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存设置失败：" + e.getMessage(), e);
        }
    }

    /**
     * 只返回用户补充的别名。系统自带别名在 HeaderCatalog 里，不重复落盘。
     */
    public Map<String, List<String>> extraAliases() {
        return data.aliases;
    }

    public Map<String, List<String>> mergedAliases() {
        return catalog.mergedAliases(data.aliases);
    }

    public List<MappingField> mappingFields() {
        List<MappingField> result = new ArrayList<>();
        Map<String, List<String>> merged = mergedAliases();
        for (HeaderCatalog.FieldDef def : catalog.fields()) {
            List<String> aliases = merged.getOrDefault(def.field(), def.aliases());
            result.add(new MappingField(def.field(), def.label(), aliases, aliases.size()));
        }
        return result;
    }

    public synchronized List<MappingField> addAlias(String field, String alias) {
        if (catalog.byField(field) == null) {
            throw new IllegalArgumentException("没有这个标准字段：" + field);
        }
        String value = TextUtils.trim(alias);
        if (value.isEmpty() || value.length() > 24) {
            throw new IllegalArgumentException("别名不能为空，也不要超过 24 个字");
        }
        List<String> list = data.aliases.computeIfAbsent(field, k -> new ArrayList<>());
        boolean exists = catalog.mergedAliases(data.aliases).getOrDefault(field, List.of()).stream()
                .anyMatch(a -> TextUtils.norm(a).equals(TextUtils.norm(value)));
        if (!exists) {
            list.add(value);
            save();
        }
        return mappingFields();
    }

    public List<RuleItem> rules() {
        List<RuleItem> result = new ArrayList<>();
        for (RuleItem def : defaults) {
            Object value = data.rules.getOrDefault(def.key(), def.value());
            result.add(new RuleItem(def.key(), def.label(), value, def.type(), def.unit(),
                    def.description(), def.group()));
        }
        return result;
    }

    public synchronized void setRule(String key, Object value) {
        boolean exists = defaults.stream().anyMatch(r -> r.key().equals(key));
        if (!exists) {
            throw new IllegalArgumentException("没有这条规则：" + key);
        }
        data.rules.put(key, value);
        save();
    }

    public List<TemplateItem> templates() {
        List<TemplateItem> result = new ArrayList<>(builtInTemplates);
        result.addAll(data.templates);
        return result;
    }

    public synchronized TemplateItem addTemplate(String name, String scene, List<String> columns, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("模板至少要有 1 列");
        }
        TemplateItem item = new TemplateItem("tpl_" + UUID.randomUUID().toString().substring(0, 8),
                name.trim(), scene == null || scene.isBlank() ? "leave" : scene, false,
                description == null ? "" : description, columns);
        data.templates.add(item);
        save();
        return item;
    }

    public TemplateItem builtIn(String id) {
        return builtInTemplates.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    public double numberRule(String key, double fallback) {
        Object value = rules().stream().filter(r -> r.key().equals(key)).findFirst()
                .map(RuleItem::value).orElse(fallback);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    public boolean boolRule(String key, boolean fallback) {
        Object value = rules().stream().filter(r -> r.key().equals(key)).findFirst()
                .map(RuleItem::value).orElse(fallback);
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    public String textRule(String key, String fallback) {
        Object value = rules().stream().filter(r -> r.key().equals(key)).findFirst()
                .map(RuleItem::value).orElse(fallback);
        return value == null ? fallback : String.valueOf(value);
    }

    public static class SettingsData {
        public Map<String, List<String>> aliases = new LinkedHashMap<>();
        public Map<String, Object> rules = new LinkedHashMap<>();
        public List<TemplateItem> templates = new ArrayList<>();
    }
}
