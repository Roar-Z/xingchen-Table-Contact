package com.xingchen.huibiao.eval;

import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.service.bridge.BridgeAgentService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 离线评测集：把 sample_data/raw 里的真实表格丢进去，完整跑一遍“扫描 → 自主规划 →
@SpringBootTest(properties = {"app.llm.enabled=false", "app.seed-demo=false"})
class BridgeEvalTest {

    // 评测用临时数据目录：不碰真实 data/ 和 frontend/downloads
    private static final Path ROOT = newTempDir();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(ROOT)));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> ROOT.resolve("data").toString());
        registry.add("app.frontend-dir", () -> ROOT.resolve("frontend").toString());
        registry.add("app.sample-dir", () -> sampleDir().toString());
    }

    @Autowired
    private BridgeAgentService agents;

    @Test
    void 示例数据集能自主跑完且结果结构稳定() throws Exception {
        Path dir = sampleDir();
        Assumptions.assumeTrue(Files.isDirectory(dir), "找不到示例数据目录：" + dir);

        List<MultipartFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(BridgeEvalTest::isTable)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> files.add(multipart(path)));
        }
        assertFalse(files.isEmpty(), "示例数据目录里没有表格");

        BridgeDataset scanned = agents.create("评测-跨系统桥接", "files", files);
        report("扫描", "识别表格 " + scanned.tables.size() + " 张：" + kinds(srcTables(scanned)));

        // 1. 扫描阶段：这几份表必须都被认出来，且业务类型判断正确
        assertTrue(scanned.tables.size() >= 3, "至少应识别出 3 张表");
        Set<String> kinds = kinds(srcTables(scanned));
        assertTrue(kinds.contains("oa_leave"), "应识别出 OA 请假单，实际：" + kinds);
        assertTrue(kinds.contains("oa_overtime"), "应识别出 OA 加班表，实际：" + kinds);

        // 判重结果要在界面上看得见：同一批加班数据的另一份导出记进 skipped，
        // 而不是悄悄少一张表、让用户以为漏扫了
        assertFalse(scanned.skipped.isEmpty(), "重复的加班表应当被记为「已跳过」，实际没有被跳过任何表");
        Set<String> skippedFiles = scanned.skipped.stream()
                .map(item -> item.file).collect(java.util.stream.Collectors.toSet());
        assertTrue(skippedFiles.stream().anyMatch(name -> name.contains("加班")),
                "被跳过的应当是一份加班表，实际：" + skippedFiles);
        for (BridgeDataset.Skipped item : scanned.skipped) {
            assertNotNull(item.duplicateOf, item.file + " 要写明和哪张表重复");
            assertTrue(item.rows > 0, item.file + " 要带上行数，界面才显示得出来");
            assertTrue(item.reason != null && !item.reason.isBlank(), item.file + " 要带上一句判定理由");
        }

        // 2. 自主处理：不给业务目标，由它按数据结构自己推断并跑完
        BridgeDataset done = agents.auto(scanned.id, "");
        assertEquals("done", done.status, "自主处理应当正常结束");
        BridgeDataset.Plan plan = done.plan;
        BridgeDataset.Result result = done.result;
        assertNotNull(plan, "应当产出方案");
        assertNotNull(result, "应当产出结果");

        report("方案", "目标：" + plan.goal + "；关联键：" + plan.joinKey
                + "；输出 " + plan.outputColumns.size() + " 列；共 " + plan.steps.size() + " 步");
        report("验收", "第 " + result.rounds + " 轮结束；正常 " + result.okRows + " 行 / 异常 "
                + result.anomalyRows + " 行；" + (result.passed ? "达标" : "未达标交付")
                + "；轨迹 " + plan.attempts.size() + " 条");
        // 报告落一份到 target/，控制台是 GBK 时中文会变问号，文件里是 UTF-8 便于直接看
        detail(scanned, plan, result);

        // 3. 结构性断言：这些一旦变化就说明实现退步了
        assertTrue(result.totalRows > 0, "总行数应大于 0");
        assertEquals(result.totalRows, result.okRows + result.anomalyRows, "正常 + 异常应等于总行数");
        // 示例数据里 HR 名册用的是 E10001、ERP 用的是 010001：归一化没生效的话这里会是 0
        assertTrue(result.okRows >= 900, "名册里 900 多人应当能对上，实际正常行：" + result.okRows);
        assertTrue(result.anomalyRows < result.totalRows, "不能整单都进异常清单");
        assertEquals("emp_no", plan.joinKey, "跨系统桥接应当按工号关联");
        assertTrue(result.columns.contains("工号"), "标准表应含工号列，实际：" + result.columns);
        assertTrue(result.columns.contains("姓名"), "标准表应含姓名列，实际：" + result.columns);
        assertTrue(result.artifacts.contains("标准表.xlsx"), "应导出标准表 xlsx");
        assertTrue(result.artifacts.contains("标准表.xls"), "应导出标准表 xls（HR 老系统只认这个）");
        assertTrue(result.artifacts.contains("异常清单.xlsx"), "应导出异常清单");
        assertTrue(plan.attempts.size() >= 1, "自主轨迹至少要有一轮");
        assertTrue(result.rounds >= 1, "至少跑一遍");

        // 4. 交付位置：文件真的落到导出目录里，而不是只记了个路径
        assertFalse(result.downloadDir == null || result.downloadDir.isBlank(), "应当回写导出目录");
        Path exportDir = Path.of(result.downloadDir);
        assertTrue(Files.isDirectory(exportDir), "导出目录应当存在：" + exportDir);
        for (String artifact : result.artifacts) {
            Path file = exportDir.resolve(artifact);
            assertTrue(Files.exists(file), "产物应当真实存在：" + file);
            assertTrue(Files.size(file) > 0, "产物不应为空文件：" + file);
        }

        report("产物", exportDir.toAbsolutePath() + " → " + result.artifacts);
    }

    // ---------------- 工具方法 ----------------

    // 把“为什么这么判”也写进报告：主表、来源、字段对齐、前几条异常原因
    private static void detail(BridgeDataset scanned, BridgeDataset.Plan plan, BridgeDataset.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 扫描到的表 ===\n");
        for (BridgeDataset.Table table : scanned.tables) {
            sb.append(table.id).append("  ").append(table.file).append("  [").append(table.kind)
                    .append("]  ").append(table.rows).append(" 行  列：").append(table.columns).append('\n');
        }
        sb.append("\n=== 方案 ===\n");
        sb.append("目标：").append(plan.goal).append('\n');
        sb.append("关联键：").append(plan.joinKey).append("（").append(plan.joinLabel).append("）\n");
        sb.append("来源：\n");
        for (BridgeDataset.Source source : plan.sources) {
            sb.append("  ").append(source.tableId).append("  ").append(source.role)
                    .append("  ").append(source.reason).append('\n');
        }
        sb.append("字段对齐：\n");
        for (BridgeDataset.Align align : plan.aligns) {
            sb.append("  ").append(align.tableId).append('.').append(align.column)
                    .append(" → ").append(align.field).append("（").append(align.label).append("）\n");
        }
        sb.append("输出列：").append(plan.outputColumns).append('\n');
        sb.append("异常规则：").append(plan.anomalyRules).append('\n');
        sb.append("说明：").append(plan.notes).append('\n');
        sb.append("\n=== 结果 ===\n");
        sb.append("总 ").append(result.totalRows).append(" 行，正常 ").append(result.okRows)
                .append(" 行，异常 ").append(result.anomalyRows).append(" 行，")
                .append(result.passed ? "达标" : "未达标").append('\n');
        sb.append("列：").append(result.columns).append('\n');
        sb.append("轨迹：\n");
        for (BridgeDataset.Attempt attempt : plan.attempts) {
            sb.append("  第 ").append(attempt.round).append(" 轮 ").append(attempt.verdict)
                    .append("  正常 ").append(attempt.okRows).append(" / 异常 ").append(attempt.anomalyRows)
                    .append("  ").append(attempt.decision == null ? "" : attempt.decision).append('\n');
        }
        sb.append("前几条异常：\n");
        int shown = 0;
        for (java.util.Map<String, String> row : result.anomalyPreview) {
            if (shown++ >= 5) break;
            sb.append("  ").append(row).append('\n');
        }
        try {
            Path out = Path.of("target", "bridge-eval-report.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString());
        } catch (IOException ignored) {
            // 报告写不出来不影响断言
        }
        System.out.println(sb);
    }

    private static List<BridgeDataset.Table> srcTables(BridgeDataset dataset) {
        return dataset.tables;
    }

    private static Set<String> kinds(List<BridgeDataset.Table> tables) {
        Set<String> out = new LinkedHashSet<>();
        for (BridgeDataset.Table table : tables) out.add(table.kind);
        return out;
    }

    private static boolean isTable(Path path) {
        return path.getFileName().toString().toLowerCase().matches(".*\\.(xls|xlsx|csv)");
    }

    private static MultipartFile multipart(Path path) {
        try {
            return new MockMultipartFile("files", path.getFileName().toString(),
                    "application/octet-stream", Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("读取示例数据失败：" + path, e);
        }
    }

    // 示例数据在仓库根目录下，后端模块的当前目录是 backend/
    private static Path sampleDir() {
        Path up = Path.of("..", "sample_data", "raw").toAbsolutePath().normalize();
        if (Files.isDirectory(up)) return up;
        Path same = Path.of("sample_data", "raw").toAbsolutePath().normalize();
        if (Files.isDirectory(same)) return same;
        return up;
    }

    private static void report(String stage, String text) {
        System.out.println("[评测] " + stage + " | " + text);
    }

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("huibiao-eval-");
        } catch (IOException e) {
            throw new IllegalStateException("创建评测临时目录失败", e);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录删不干净不影响评测结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
