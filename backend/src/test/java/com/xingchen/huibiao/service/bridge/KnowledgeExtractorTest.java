package com.xingchen.huibiao.service.bridge;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 知识库取字与切片：这部分决定了"用户传上去的文档到底有没有被读懂"。
class KnowledgeExtractorTest {

    @Test
    void 文本文件按原样取字并支持格式判断() {
        assertTrue(KnowledgeExtractor.supports("请假制度.txt"));
        assertTrue(KnowledgeExtractor.supports("口径说明.PDF"));
        assertFalse(KnowledgeExtractor.supports("旧版.doc"));
        assertFalse(KnowledgeExtractor.supports("压缩包.rar"));

        assertEquals("PDF", KnowledgeExtractor.kindOf("制度.pdf"));
        assertEquals("Word", KnowledgeExtractor.kindOf("制度.docx"));
        assertEquals("Excel", KnowledgeExtractor.kindOf("对照表.xlsx"));
    }

    // docx 就是 zip 里的 document.xml：段落要断行、制表符要保留、XML 实体要还原
    @Test
    void docx段落能拆成可读文本() throws Exception {
        String xml = "<?xml version=\"1.0\"?><w:document><w:body>"
                + "<w:p><w:r><w:t>第三条 年假余额按工龄计算</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>入职满一年 5 天</w:t></w:r><w:r><w:tab/><w:t>满十年 10 天</w:t></w:r></w:p>"
                + "</w:body></w:document>";
        byte[] bytes = zipWith("word/document.xml", xml);
        String text = KnowledgeExtractor.fromDocx(bytes);
        assertTrue(text.contains("第三条 年假余额按工龄计算"), "正文应当取到：" + text);
        assertTrue(text.contains("满十年 10 天"));
        assertTrue(text.contains("\n"), "段落之间应当有换行");
        assertFalse(text.contains("<w:"), "不能把标签留在正文里");

        // 有些打包工具把路径写成 word\document.xml，也要能读到正文
        byte[] backslash = zipWith("word\\document.xml", xml);
        assertTrue(KnowledgeExtractor.fromDocx(backslash).contains("年假余额"));
    }

    // xlsx 按工作表拍平成行，制度附录里的对照表靠这个能被检索到
    @Test
    void excel按工作表拍平成文本() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("假别对照");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("假别");
            row.createCell(1).setCellValue("扣款系数");
            var row2 = sheet.createRow(1);
            row2.createCell(0).setCellValue("事假");
            row2.createCell(1).setCellValue("1.0");
            workbook.write(out);
            bytes = out.toByteArray();
        }
        String text = KnowledgeExtractor.fromExcel(bytes);
        assertTrue(text.contains("【工作表：假别对照】"), text);
        assertTrue(text.contains("事假\t1.0"), "单元格应当用制表符分隔：" + text);
    }

    // PDF 内容流里的括号字符串与转义：这是文字版 PDF 取字的最小单元
    @Test
    void pdf内容流里的文字能取出来() {
        String content = "BT /F1 12 Tf 72 720 Td (请假制度) Tj 0 -18 Td (事假扣当天工资，年假不扣) Tj ET";
        String text = KnowledgeExtractor.textFromContent(content);
        assertTrue(text.contains("请假制度"), text);
        assertTrue(text.contains("年假不扣"));
        assertTrue(text.contains("\n"), "Td 换行要保留");

        // 带转义的括号串：余下的是 (示例) 这种写法，不能把反斜杠当正文
        String escaped = "BT (名称：\\(人事\\)口径) Tj ET";
        String decoded = KnowledgeExtractor.textFromContent(escaped);
        assertTrue(decoded.contains("(人事)"), decoded);
        assertFalse(decoded.contains("\\("), decoded);
    }

    // 切片：长度受控、相邻片有重叠，否则一条规则正好被切在边界上就检索不到
    @Test
    void 切片长度受控且相邻片有重叠() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("第").append(i).append("条：请假需提前一天在系统里提交，事假按天扣款。\n");
        }
        List<BridgeKnowledge.Chunk> chunks = BridgeKnowledge.chunk(sb.toString());
        assertTrue(chunks.size() >= 2, "长文档应当切成多片");
        for (BridgeKnowledge.Chunk chunk : chunks) {
            assertTrue(chunk.text.length() <= 900, "单片不应过长：" + chunk.text.length());
        }
        String first = chunks.get(0).text;
        String second = chunks.get(1).text;
        assertTrue(second.length() > 0);
        // 重叠意味着第二片的开头出现在第一片的正文里（按内容比对，不依赖具体偏移）
        String secondHead = second.substring(0, Math.min(12, second.length()));
        assertTrue(first.contains(secondHead), "相邻片应当有重叠，避免切断一句话");
    }

    // 短文档只切一片：以前末尾会反复重叠着切，几十片一模一样的尾巴会挤掉真正相关的内容
    @Test
    void 短文档只切一片不重复兜圈() {
        String text = "年假制度：当年可休天数按剩余月数折算，年假余额为负表示已超休。";
        List<BridgeKnowledge.Chunk> chunks = BridgeKnowledge.chunk(text);
        assertEquals(1, chunks.size(), "不足一片的正文不该被重复切出来");
        assertEquals(text, chunks.get(0).text);
    }

    // 词组：中文二元、英文按词；标点不参与，否则"（"会抢走相似度
    @Test
    void 词组特征能忽略标点并区分内容() {
        Set<Integer> a = BridgeKnowledge.gramsOf("年假余额（按工龄）");
        Set<Integer> b = BridgeKnowledge.gramsOf("年假余额(按工龄)");
        assertEquals(a, b, "中英文括号不该改变特征");
        assertFalse(BridgeKnowledge.gramsOf("年假余额").equals(BridgeKnowledge.gramsOf("请假天数")));
        assertTrue(BridgeKnowledge.gramsOf(" ").isEmpty(), "只有空白时不产生特征");
    }

    // 长问句不能被客套话稀释：整句余弦算不到阈值，按句子分开算要能命中
    @Test
    void 长问句也能命中制度条款() {
        String policy = "年假制度（2026 版）一、年假余额按入职日期折算：当年可休天数 = 15 天 × (当年剩余月数 / 12)。"
                + "二、请假天数按工作日计算，单次请假超过 5 天需部门负责人审批。"
                + "三、加班小时可按 1:1 折算调休。四、离职员工年假余额按实际在职月份折算。";
        double hit = BridgeKnowledge.scoreOf(
                BridgeKnowledge.segmentsOf("年假余额怎么算？如果参考知识里有相关条款，请说明出自哪份文件。"),
                BridgeKnowledge.gramsOf(policy));
        assertTrue(hit >= 0.06, "长问句也要能命中制度条款：" + hit);

        double miss = BridgeKnowledge.scoreOf(
                BridgeKnowledge.segmentsOf("今天天气怎么样，适合出去散步吗"),
                BridgeKnowledge.gramsOf(policy));
        assertTrue(miss < 0.06, "无关问句不该命中：" + miss);
    }

    // 用户换个说法也要能命中：「请假的额度还剩多少」和制度里的「年假余额」意思一样
    @Test
    void 换一种说法也能命中制度条款() {
        Set<Integer> policy = BridgeKnowledge.gramsOf("年假制度：年假余额按入职日期折算，当年可休天数 = 15 天 × (剩余月数 / 12)。"
                + "事假不抵扣年假，年假余额为负表示已超休。");
        double hit = BridgeKnowledge.matchScore("请假的额度还剩多少天，用完是不是要扣钱？", policy);
        assertTrue(hit >= 0.06, "同义改写应当命中：" + hit);

        // 不相关的问句仍然不能命中，不能被同义词表带偏
        double miss = BridgeKnowledge.matchScore("今天天气怎么样，适合出去散步吗", policy);
        assertTrue(miss < 0.06, "无关问句不该命中：" + miss);
    }

    // 给提示词用的口径标签：模型先知道这句在问哪类规则
    @Test
    void 能识别问句涉及的业务口径() {
        assertTrue(BridgeKnowledge.topicsOf("年假还剩多少天").contains("年假"));
        assertTrue(BridgeKnowledge.topicsOf("休假的额度还剩多少").contains("余额"));
        assertTrue(BridgeKnowledge.topicsOf("加班小时怎么折算").contains("加班"));
        assertTrue(BridgeKnowledge.topicsOf("今天天气怎么样").isEmpty());
    }

    // 覆盖率要起作用：整句余弦在长切片上会被稀释，关键词命中的比例不能一起被拖低
    @Test
    void 短问句在长切片上不会被稀释() {
        String longPolicy = "年假制度（2026 版）一、年假余额按入职日期折算：当年可休天数 = 15 天 × (当年剩余月数 / 12)。"
                + "二、请假天数按工作日计算，单次请假超过 5 天需部门负责人审批。三、加班小时可按 1:1 折算调休。"
                + "四、离职员工年假余额按实际在职月份折算。五、婚假 3 天，产假按国家规定执行。"
                + "六、病假需提供医院证明，超过 2 天扣绩效。七、调休需在当年 12 月 31 日前用完，过期作废。";
        Set<Integer> grams = BridgeKnowledge.gramsOf(longPolicy);
        double hit = BridgeKnowledge.matchScore("年假还剩几天", grams);
        assertTrue(hit >= BridgeKnowledge.MIN_SCORE, "短问句也要过召回阈值：" + hit);
        // 长切片里塞了一堆别的话题，分数线不能因此被拉到阈值以下
        double noise = BridgeKnowledge.matchScore("年假还剩几天", BridgeKnowledge.gramsOf(
                "办公室卫生值日安排：每组两人，每周轮换一次，周末由值班人员检查饮水机和水电开关。"));
        assertTrue(hit > noise, "命中制度的分数要高于无关条款：" + hit + " vs " + noise);
    }

    private static byte[] zipWith(String entryName, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
