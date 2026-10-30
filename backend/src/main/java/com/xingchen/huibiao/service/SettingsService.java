package com.xingchen.huibiao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.model.RuleItem;
import com.xingchen.huibiao.model.TemplateItem;
import com.xingchen.huibiao.util.TextUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 设置：别名、计算口径、自定义模板，整体读写，落 SQLite 的 kv 表（scope = settings）。
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String SCOPE = "settings";

    private final DataPaths paths;
    private final SqliteDb db;
    private final ObjectMapper mapper;
    private final HeaderCatalog catalog;
    private SettingsData data = new SettingsData();
    private final List<RuleItem> defaults = new ArrayList<>();
    private final List<TemplateItem> builtInTemplates = new ArrayList<>();

    public SettingsService(DataPaths paths, SqliteDb db, ObjectMapper mapper, HeaderCatalog catalog) {
        this.paths = paths;
        this.db = db;
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
        migrateLegacy();
        String body = db.kv(SCOPE);
        if (body == null || body.isBlank()) {
            return;
        }
        SettingsData loaded = db.fromJson(body, SettingsData.class);
        if (loaded == null) {
            log.warn("设置读取失败，先用默认值");
            return;
        }
        if (loaded.aliases != null) {
            data.aliases = loaded.aliases;
        }
        if (loaded.rules != null) {
            data.rules = loaded.rules;
        }
        if (loaded.templates != null) {
            data.templates = loaded.templates;
        }
        if (loaded.exportDir != null) {
            data.exportDir = loaded.exportDir;
        }
    }

    // 老版本的 settings.json 只在库里没有这份数据时导入一次，导完改名留档。
    private void migrateLegacy() {
        Path file = paths.settingsFile();
        if (db.kv(SCOPE) != null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            SettingsData loaded = mapper.readValue(file.toFile(), SettingsData.class);
            if (loaded == null) {
                loaded = new SettingsData();
            }
            db.kvPut(SCOPE, loaded);
            log.info("已把旧设置导入数据库");
            db.retireLegacy(file);
        } catch (Exception e) {
            log.warn("旧设置导入失败，先用默认值：{}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            db.kvPut(SCOPE, data);
        } catch (RuntimeException e) {
            throw new IllegalStateException("保存设置失败：" + e.getMessage(), e);
        }
    }

    public Map<String, List<String>> mergedAliases() {
        return catalog.mergedAliases(data.aliases);
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

    public List<TemplateItem> templates() {
        List<TemplateItem> result = new ArrayList<>(builtInTemplates);
        result.addAll(data.templates);
        return result;
    }

    // 智能体模式的导出目录：空字符串表示用默认目录（前端工程下的 downloads）。
    public String exportDir() {
        return data.exportDir == null ? "" : data.exportDir.trim();
    }

    // 设置导出目录：传空字符串表示恢复默认。
    public synchronized String setExportDir(String dir) {
        String value = TextUtils.trim(dir == null ? "" : dir);
        if (value.isEmpty()) {
            data.exportDir = "";
            save();
            return "";
        }
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("请填绝对路径，例如 D:\\表格导出");
        }
        if (value.length() > 260) {
            throw new IllegalArgumentException("路径太长了，换一个短一点的目录");
        }
        try {
            Files.createDirectories(path);
            if (!Files.isWritable(path)) {
                throw new IllegalArgumentException("这个目录不可写：" + value);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("这个目录无法创建或写入：" + value);
        }
        data.exportDir = path.toAbsolutePath().normalize().toString();
        save();
        return data.exportDir;
    }

    public TemplateItem builtIn(String id) {
        for (TemplateItem item : builtInTemplates) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    public double numberRule(String key, double fallback) {
        Object value = ruleValue(key, fallback);
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
        Object value = ruleValue(key, fallback);
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    public String textRule(String key, String fallback) {
        Object value = ruleValue(key, fallback);
        return value == null ? fallback : String.valueOf(value);
    }

    // 三个 xxxRule 都要按 key 找一条规则，找的写法一模一样，抽出来省得抄三遍
    private Object ruleValue(String key, Object fallback) {
        for (RuleItem rule : rules()) {
            if (rule.key().equals(key)) {
                return rule.value();
            }
        }
        return fallback;
    }

    public static class SettingsData {
        public Map<String, List<String>> aliases = new LinkedHashMap<>();
        public Map<String, Object> rules = new LinkedHashMap<>();
        public List<TemplateItem> templates = new ArrayList<>();
        // 智能体模式的导出目录，空表示用默认的前端 downloads 目录
        public String exportDir = "";
    }
}
