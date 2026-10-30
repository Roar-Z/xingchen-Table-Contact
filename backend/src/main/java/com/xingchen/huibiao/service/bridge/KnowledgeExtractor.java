package com.xingchen.huibiao.service.bridge;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// 知识库文件的取字：把用户上传的文档、表格、PDF 变成一段纯文本，后面再切片、检索。
public final class KnowledgeExtractor {

    // 支持的扩展名：和前端 accept 保持一致，改这里时记得同步
    public static final Set<String> SUFFIXES = new LinkedHashSet<>(List.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "log", "docx", "xlsx", "xls", "pdf"));

    // 单个文件最多取多少字：知识库是给模型当参考的，再多也塞不进上下文，只会拖慢检索
    public static final int MAX_CHARS = 200_000;

    // 表格最多读多少行：制度类表格通常几十行，几千行的那种是业务数据，不该进知识库
    private static final int MAX_SHEET_ROWS = 2_000;

    private static final Pattern XML_TAG = Pattern.compile("<[^>]+>");
    // PDF 内容流里的文字只有两种写法：括号串（可带转义）与十六进制串
    private static final Pattern PDF_TOKEN = Pattern.compile("\\((?:[^()\\\\]|\\\\.)*\\)|<[0-9A-Fa-f\\s]+>",
            Pattern.DOTALL);
    // PDF 内容流里这三类运算符意味着换行或间距，保留下来后面才好切句
    private static final Pattern PDF_BREAK = Pattern.compile("(?:Td|TD|T\\*|ET)");

    private KnowledgeExtractor() {
    }

    // 取字结果：text 是正文，note 是给用户看的说明（例如扫描件取不到字）
    public record Extracted(String text, String note) {
    }

    public static String suffix(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean supports(String name) {
        return SUFFIXES.contains(suffix(name));
    }

    // 人类可读的类型名，前端列表里显示用
    public static String kindOf(String name) {
        return switch (suffix(name)) {
            case "pdf" -> "PDF";
            case "docx" -> "Word";
            case "xlsx", "xls" -> "Excel";
            case "csv", "tsv" -> "CSV";
            case "md", "markdown" -> "Markdown";
            case "json" -> "JSON";
            default -> "文本";
        };
    }

    // 按后缀取字。抛出的异常带上文件名，调用方可以原样写进"跳过原因"。
    public static Extracted extract(Path file) throws IOException {
        String name = file.getFileName().toString();
        String suffix = suffix(name);
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            return new Extracted("", "文件是空的");
        }
        return switch (suffix) {
            case "pdf" -> fromPdf(bytes);
            case "docx" -> new Extracted(clip(fromDocx(bytes)), "");
            case "xlsx", "xls" -> new Extracted(clip(fromExcel(bytes)), "");
            default -> new Extracted(clip(readText(bytes)), "");
        };
    }

    // 纯文本

    // 文本类文件按 UTF-8 读；中文 Excel 导出的 csv 常是 GBK，解不成 UTF-8 就换 GBK 再试，两个都不认就原样返回
    private static String readText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
            return utf8.replace("\uFFFD", "");
        }
    }

    // docx

    // docx 本质是 zip，正文在 word/document.xml。段落边界换成换行、制表符保留，其余标签直接去掉
    static String fromDocx(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                // 有些打包工具（含部分脚本生成的 docx）用反斜杠分隔，按标准路径比一下才不会漏读
                if (!"word/document.xml".equals(entry.getName().replace('\\', '/'))) continue;
                return cleanDocxXml(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return "";
        }
        return "";
    }

    static String cleanDocxXml(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        String text = xml
                .replaceAll("(?i)</w:p>", "\n")
                .replaceAll("(?i)<w:tab[^>]*/>", "\t")
                .replaceAll("(?i)<w:br[^>]*/>", "\n")
                .replaceAll("(?i)</w:tc>", "\t")
                .replaceAll("(?i)</w:tr>", "\n");
        text = XML_TAG.matcher(text).replaceAll("");
        return unescapeXml(text);
    }

    private static String unescapeXml(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#xA;", "\n")
                .replaceAll("&#(\\d+);", "")
                .replace("&amp;", "&");
    }

    // xlsx / xls

    // 表格按"工作表 → 行 → 单元格"拍平成文本：每行一行、单元格用制表符分隔。
    static String fromExcel(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("【工作表：").append(sheet.getSheetName()).append("】\n");
                int last = Math.min(sheet.getLastRowNum(), MAX_SHEET_ROWS);
                for (int r = 0; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                        cells.add(value);
                    }
                    // 整行为空就跳过：空行对检索没有意义，还占切片名额
                    boolean empty = true;
                    for (String cell : cells) {
                        if (!cell.isEmpty()) {
                            empty = false;
                            break;
                        }
                    }
                    if (empty) continue;
                    sb.append(String.join("\t", cells)).append("\n");
                }
            }
        } catch (Exception e) {
            // 加密、损坏、扩展名与内容不符都会落到这里，调用方按"取不到字"处理
            return "";
        }
        return sb.toString();
    }

    // PDF

    // PDF 取字的最简可行版本：找到所有内容流，先试 FlateDecode 解压，再按 Tj/TJ 操作符抠字符串，抠不出来就返回空
    static Extracted fromPdf(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        String raw = latin(bytes);
        int from = 0;
        while (true) {
            int start = raw.indexOf("stream", from);
            if (start < 0) break;
            int bodyStart = start + "stream".length();
            if (bodyStart < raw.length() && raw.charAt(bodyStart) == '\r') bodyStart++;
            if (bodyStart < raw.length() && raw.charAt(bodyStart) == '\n') bodyStart++;
            int end = raw.indexOf("endstream", bodyStart);
            if (end < 0) break;
            String decoded = decodeStream(bytes, bodyStart, end);
            if (decoded != null && decoded.contains("BT")) {
                sb.append(textFromContent(decoded)).append("\n");
            }
            from = end + "endstream".length();
        }
        String text = normalize(sb.toString());
        if (text.length() < 20) {
            return new Extracted("", "这份 PDF 里取不到文字（可能是扫描件或图片版），"
                    + "请上传 Word / Excel 原文件，或先把 PDF 转成文字版再传");
        }
        return new Extracted(clip(text), "");
    }

    // 试解压：FlateDecode 是 PDF 里最常见的压缩；不是压缩流时返回原文（有少数 PDF 直接存明文）
    private static String decodeStream(byte[] bytes, int from, int to) {
        byte[] slice = new byte[Math.max(0, to - from)];
        System.arraycopy(bytes, from, slice, 0, slice.length);
        try {
            Inflater inflater = new Inflater(true);
            inflater.setInput(slice);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(slice.length * 2 + 16);
            byte[] buffer = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) break;
                out.write(buffer, 0, n);
            }
            inflater.end();
            String inflated = latin(out.toByteArray());
            if (inflated.contains("BT") || inflated.contains("Tj")) return inflated;
        } catch (Exception ignored) {
            // 不是 Flate 流（例如图像、字体），继续往下走原文判断
        }
        String plain = latin(slice);
        return plain.contains("BT") ? plain : null;
    }

    // 从内容流里把绘制文字的字符串按出现顺序拼出来，换行运算符换成 \n
    static String textFromContent(String content) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = Pattern.compile("BT(.*?)ET", Pattern.DOTALL).matcher(content);
        boolean any = false;
        while (matcher.find()) {
            any = true;
            out.append(tokensOf(matcher.group(1))).append("\n");
        }
        // 有些生成器不写 BT/ET，直接把整段当内容扫一遍，宁可多一点也不漏
        if (!any) out.append(tokensOf(content));
        return out.toString();
    }

    private static String tokensOf(String block) {
        StringBuilder sb = new StringBuilder();
        Matcher token = PDF_TOKEN.matcher(block);
        int cursor = 0;
        while (token.find()) {
            // 两个字符串之间若有 Td/TD/T*（换行或换位），说明它们不在同一行，补一个换行
            if (PDF_BREAK.matcher(block.substring(cursor, token.start())).find()) sb.append('\n');
            String value = token.group();
            if (value.startsWith("(")) {
                sb.append(pdfUnescape(value));
            } else {
                sb.append(hexGroup(value.substring(1, value.length() - 1)));
            }
            cursor = token.end();
        }
        return sb.toString();
    }

    // 去掉 PDF 字符串里的转义：\\( \\) \\\\ \\n \\t 与三位八进制
    private static String pdfUnescape(String token) {
        String body = token.substring(1, token.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            i++;
            if (i >= body.length()) break;
            char next = body.charAt(i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'b', 'f' -> sb.append(' ');
                case '(', ')', '\\' -> sb.append(next);
                default -> {
                    if (next >= '0' && next <= '7') {
                        // 八进制最多三位，越界就按当前已有的位数算
                        int value = next - '0';
                        int digits = 1;
                        while (digits < 3 && i + 1 < body.length()
                                && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') {
                            i++;
                            digits++;
                            value = value * 8 + (body.charAt(i) - '0');
                        }
                        sb.append((char) value);
                    } else {
                        sb.append(next);
                    }
                }
            }
        }
        return sb.toString();
    }

    // PDF 里的 <...> 多为 UTF-16BE 或 Latin-1 编码的短串，长度不像就丢弃，免得塞进乱码
    private static String hexGroup(String hex) {
        String clean = hex.replaceAll("\\s", "");
        if (clean.length() < 4) return "";
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] != 0) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        String latin = latin(bytes);
        return latin.isBlank() ? "" : latin;
    }

    private static String latin(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    // 公共

    // 统一空白：连续空行压成一个、行尾空格去掉；检索切片时按行拼回去更好切句
    static String normalize(String text) {
        if (text == null) return "";
        String flattened = text.replaceAll("[\\r\\u000B\\f]+", "\n")
                .replaceAll("[ \\t]{2,}", " ");
        StringBuilder sb = new StringBuilder(flattened.length());
        int blank = 0;
        for (String line : flattened.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                if (blank >= 1) continue;
                blank++;
                sb.append('\n');
                continue;
            }
            blank = 0;
            sb.append(trimmed).append('\n');
        }
        return sb.toString().strip();
    }

    private static String clip(String text) {
        String normalized = normalize(text);
        return normalized.length() <= MAX_CHARS ? normalized : normalized.substring(0, MAX_CHARS);
    }
}
