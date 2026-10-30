package com.xingchen.huibiao.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 智能体模式（跨系统桥接）的现场：一次上传的整个数据集、规划方案、执行结果都在这里。
public class BridgeDataset {

    // 三种访问级别：手动审批 / 自动审批 / 完全访问
    public static final String ACCESS_MANUAL = "manual";
    public static final String ACCESS_AUTO = "auto";
    public static final String ACCESS_FULL = "full";

    public String id;
    public String name;
    // zip / folder / files
    public String sourceType = "files";
    public String createdAt;
    // scanned / awaiting / planned / running / done / failed
    public String status = "scanned";
    public String instruction = "";
    // 访问级别：manual 每次规划完停下来等人点确认，auto 直接跑
    public String access = ACCESS_AUTO;
    // 和智能体的多轮对话：追问为什么这么算、再顺带改一处，都记在这里
    public List<Chat> chat = new ArrayList<>();
    public List<Table> tables = new ArrayList<>();
    // 扫描时被判为"同一批数据的另一份导出"、因此没参与计算的表。
    public List<Skipped> skipped = new ArrayList<>();
    public Plan plan;
    public Result result;
    public List<String> notes = new ArrayList<>();

    // 一张被扫描出来的表：叫什么、属于哪个系统、表头和样例
    public static class Table {
        public String id;
        public String file;
        public String stored;
        public String kind = "unknown";
        public String kindLabel = "普通表";
        public int rows;
        public List<String> columns = new ArrayList<>();
        public Map<String, String> sample = new LinkedHashMap<>();
    }

    // 被判重跳过、未参与计算的一张表：和谁重复、按什么判的、原本有多少行列
    public static class Skipped {
        public String file;
        public String kind = "unknown";
        public String kindLabel = "普通表";
        public int rows;
        public List<String> columns = new ArrayList<>();
        // 和哪张表重复（保留的那张）
        public String duplicateOf;
        // 判定理由，直接给人看
        public String reason;
    }

    // 规划方案：全部字段都由智能体生成，自主模式会自己验收、自己修正再交付
    public static class Plan {
        public String summary;
        // 本次的业务目标：用户没写时由智能体按数据结构自己推断
        public String goal;
        // 验收标准（给人看的口径），和 passRatio 一起决定这一单算不算做完
        public List<String> acceptance = new ArrayList<>();
        // 正常行占比要达到多少才算达标，默认 0.9
        public double passRatio = 0.9;
        // 这一单是不是自主跑出来的（自己定目标、自己执行、自己验收）
        public boolean auto;
        // 自主循环的每一轮：跑了多少、看到什么、做了什么决定
        public List<Attempt> attempts = new ArrayList<>();
        public List<Step> steps = new ArrayList<>();
        public List<Source> sources = new ArrayList<>();
        public List<Align> aligns = new ArrayList<>();
        public List<Compute> computes = new ArrayList<>();
        public List<String> filters = new ArrayList<>();
        // 展示用的异常规则文案，和 anomalyChecks 一一对应
        public List<String> anomalyRules = new ArrayList<>();
        // 执行时真正逐条跑的异常检查，规划器和模型都往这里写
        public List<AnomalyCheck> anomalyChecks = new ArrayList<>();
        public List<String> outputColumns = new ArrayList<>();
        public String joinKey;
        public String joinLabel;
        // 执行时用到的结构化筛选条件，界面上用 filters 的文字描述展示
        public String dateField;
        public String dateFrom;
        public String dateTo;
        public String statusFilter;
        public List<String> notes = new ArrayList<>();
        // rules / llm
        public String mode = "rules";
        // 这一单有没有用到经验记忆里同类数据集的配置，运行观测的「经验命中」按它算
        public boolean lessonHit;
        public boolean confirmed;
        public String plannedAt;
    }

    public static class Step {
        public String tool;
        public String title;
        public String detail;
        public String state = "done";
        public long ms;
    }

    // 自主循环的一轮记录：先跑一遍看结果，再决定要不要改方案重跑。
    public static class Attempt {
        public int round;
        public int okRows;
        public int anomalyRows;
        // 本轮观察到的结论和采取的动作，空表示这一轮只是执行
        public String decision = "";
        public String verdict = "";
        public long ms;
    }

    public static class Source {
        public String tableId;
        public String role;
        public String reason;
    }

    // 一个字段对齐：标准字段 ← 某张表的某一列
    public static class Align {
        public String field;
        public String label;
        public String tableId;
        public String column;
        // 规则 / AI / 自定义
        public String origin = "规则";
        public double confidence = 0.85;
    }

    public static class Compute {
        public String name;
        public List<String> plus = new ArrayList<>();
        public List<String> minus = new ArrayList<>();
        public String origin = "规则";
    }

    // 一条能真正执行的异常检查：type 决定怎么判，label 指向标准字段，reason 是写进异常清单的说明。
    public static class AnomalyCheck {
        // join / blank / date / numeric / garbled / duplicate
        public String type;
        public String label = "";
        public String reason;

        public AnomalyCheck() {
        }

        public AnomalyCheck(String type, String label, String reason) {
            this.type = type;
            this.label = label == null ? "" : label;
            this.reason = reason;
        }
    }

    public static class Result {
        public int totalRows;
        public int okRows;
        public int anomalyRows;
        public int tableCount;
        public List<String> columns = new ArrayList<>();
        public List<Map<String, String>> preview = new ArrayList<>();
        public List<Map<String, String>> anomalyPreview = new ArrayList<>();
        public List<String> artifacts = new ArrayList<>();
        public long elapsedMs;
        public String finishedAt;
        public String mode = "rules";
        // 这次结果是按“执行后自我评估”修正方案又跑了一遍得到的
        public boolean revised;
        // 是否达到验收标准：达标才算这一单真正做完
        public boolean passed;
        // 自主循环实际跑了几轮
        public int rounds;
        // 自动修复的单元格数：全角/空格/零宽字符这类不影响语义的脏值
        public int repairs;
        // 导出文件放在哪个目录（绝对路径）。跑完会再拷一份到前端的 downloads
        public String downloadDir;
    }

    // 一轮对话。role 分 user / agent，action 记这轮干了什么（answer / rerun）
    public static class Chat {
        public String role;
        public String text;
        public String at;
        public String action = "answer";
        // 重跑时这一轮跑了几轮、正常/异常多少行
        public int rounds;
        public int okRows;
        public int anomalyRows;
        public boolean passed;

        public Chat() {
        }

        public Chat(String role, String text, String at) {
            this.role = role;
            this.text = text;
            this.at = at;
        }
    }
}
