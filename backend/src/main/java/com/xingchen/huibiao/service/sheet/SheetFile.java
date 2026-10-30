package com.xingchen.huibiao.service.sheet;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// 表格智作的读写底座：内存里是一张二维表，落到磁盘就是 xlsx。
public final class SheetFile {

    private static final Pattern PLAIN_NUMBER = Pattern.compile("[-+]?\\d{1,15}(\\.\\d+)?");
    // 系统补出来的列名“列3”“列12”，老版本把标题当表头时会出现一排这种名字。
    private static final Pattern PLACEHOLDER = Pattern.compile("列\\d+");
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.US);

    private SheetFile() {
    }

    public static class Table {
        public List<String> columns = new ArrayList<>();
        public List<List<String>> rows = new ArrayList<>();

        public Table() {
        }

        public Table(List<String> columns, List<List<String>> rows) {
            this.columns = new ArrayList<>(columns);
            this.rows = new ArrayList<>(rows);
        }

        public int columnIndex(String column) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).equals(column)) {
                    return i;
                }
            }
            return -1;
        }

        public String cell(int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size() || columnIndex < 0) {
                return "";
            }
            List<String> row = rows.get(rowIndex);
            return columnIndex < row.size() ? row.get(columnIndex) : "";
        }

        public void setCell(int rowIndex, int columnIndex, String value) {
            List<String> row = rows.get(rowIndex);
            while (row.size() < columns.size()) {
                row.add("");
            }
            row.set(columnIndex, value == null ? "" : value);
        }

        public Table copy() {
            Table copy = new Table(columns, new ArrayList<>());
            for (List<String> row : rows) {
                copy.rows.add(new ArrayList<>(row));
            }
            return copy;
        }
    }

    public static Table read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return new Table();
        }
        try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
            return read(workbook);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("读取表格失败：" + e.getMessage(), e);
        }
    }

    public static Table read(Workbook workbook) {
        Sheet sheet = workbook.getSheetAt(0);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        int first = Math.max(0, sheet.getFirstRowNum());
        int last = sheet.getLastRowNum();

        List<List<String>> raw = new ArrayList<>();
        int width = 0;
        for (int r = first; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int cells = Math.max(0, row.getLastCellNum());
            width = Math.max(width, cells);
            List<String> values = new ArrayList<>();
            boolean any = false;
            for (int c = 0; c < cells; c++) {
                String text = textOf(row.getCell(c), evaluator);
                if (!text.isEmpty()) {
                    any = true;
                }
                values.add(text);
            }
            if (any) {
                raw.add(values);
            }
        }
        if (raw.isEmpty()) {
            return new Table();
        }

        // 表头不一定在第一行：合并单元格的大标题、填报单位之类的行要跳过。
        // 多级表头（“早餐 / 费用 / 菜品名称”这种）要连着一层一起认，否则会丢掉上一层的分组名。
        int headerStart = 0;
        int headerEnd = 0;
        int limit = Math.min(raw.size(), 12);
        for (int i = 0; i < limit; i++) {
            if (filled(raw.get(i)) < 2 || placeholderCount(raw.get(i)) >= 2) {
                continue;
            }
            headerStart = i;
            headerEnd = i;
            while (headerEnd + 1 < raw.size() && headerEnd + 1 < headerStart + 4
                    && filled(raw.get(headerEnd + 1)) > filled(raw.get(headerEnd))
                    && looksLikeHeader(raw.get(headerEnd + 1))) {
                headerEnd++;
            }
            break;
        }

        // 表头和整列都空着的列直接丢掉，免得结果表里出现一排“列2…列18”的空列
        int headWidth = Math.max(width, raw.get(headerStart).size());
        for (int i = headerStart; i <= headerEnd; i++) {
            headWidth = Math.max(headWidth, raw.get(i).size());
        }
        List<String> rawColumns = new ArrayList<>();
        List<Integer> keep = new ArrayList<>();
        for (int c = 0; c < headWidth; c++) {
            String name = headerName(raw, headerStart, headerEnd, c);
            if (name.isBlank() && !hasValue(raw, headerEnd, c)) {
                continue;
            }
            rawColumns.add(name.isBlank() ? "列" + (c + 1) : name);
            keep.add(c);
        }
        List<String> columns = dedupe(rawColumns);

        List<List<String>> rows = new ArrayList<>();
        for (int i = headerEnd + 1; i < raw.size(); i++) {
            List<String> source = raw.get(i);
            List<String> row = new ArrayList<>(columns.size());
            for (int c : keep) {
                row.add(c < source.size() ? source.get(c) : "");
            }
            rows.add(row);
        }
        return new Table(columns, rows);
    }

    // 多级表头拼成一列名：上层是分组（早餐），下层是具体字段（费用），中间空格隔开。
    private static String headerName(List<List<String>> raw, int start, int end, int column) {
        StringBuilder name = new StringBuilder();
        String previous = "";
        for (int i = start; i <= end; i++) {
            List<String> row = raw.get(i);
            String value = column < row.size() ? row.get(column) : "";
            if (value == null || value.isBlank() || value.equals(previous)) {
                continue;
            }
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(value);
            previous = value;
        }
        return name.toString();
    }

    // 表头行都是短标签，出现数字或长句子就当成数据行。
    private static boolean looksLikeHeader(List<String> row) {
        for (String value : row) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.length() > 12 || PLAIN_NUMBER.matcher(value).matches()) {
                return false;
            }
        }
        return true;
    }

    private static int placeholderCount(List<String> row) {
        int count = 0;
        for (String value : row) {
            if (value != null && PLACEHOLDER.matcher(value.trim()).matches()) {
                count++;
            }
        }
        return count;
    }

    private static int filled(List<String> row) {
        int count = 0;
        for (String value : row) {
            if (value != null && !value.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasValue(List<List<String>> raw, int headerIndex, int column) {
        for (int i = headerIndex + 1; i < raw.size(); i++) {
            List<String> row = raw.get(i);
            if (column < row.size() && row.get(column) != null && !row.get(column).isBlank()) {
                return true;
            }
        }
        return false;
    }

    public static void write(Path file, Table table) throws IOException {
        Files.createDirectories(file.getParent());
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            CellStyle head = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            head.setFont(font);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            Row header = sheet.createRow(0);
            for (int c = 0; c < table.columns.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(table.columns.get(c));
                cell.setCellStyle(head);
                sheet.setColumnWidth(c, Math.min(255 * 42, Math.max(12, table.columns.get(c).length() + 6) * 256));
            }

            for (int r = 0; r < table.rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = table.rows.get(r);
                for (int c = 0; c < table.columns.size(); c++) {
                    String value = c < values.size() ? values.get(c) : "";
                    if (value == null || value.isEmpty()) {
                        continue;
                    }
                    Cell cell = row.createCell(c);
                    if (isPlainNumber(value)) {
                        cell.setCellValue(Double.parseDouble(value));
                    } else {
                        cell.setCellValue(value);
                    }
                }
            }
            sheet.createFreezePane(0, 1);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        }
    }

    public static List<String> numericColumns(Table table) {
        List<String> numeric = new ArrayList<>();
        for (int c = 0; c < table.columns.size(); c++) {
            boolean hasValue = false;
            boolean allNumber = true;
            for (List<String> row : table.rows) {
                String value = c < row.size() ? row.get(c) : "";
                if (value.isEmpty()) {
                    continue;
                }
                hasValue = true;
                if (!PLAIN_NUMBER.matcher(value).matches()) {
                    allNumber = false;
                    break;
                }
            }
            if (hasValue && allNumber) {
                numeric.add(table.columns.get(c));
            }
        }
        return numeric;
    }

    // 用文件名当表名，去掉扩展名和路径里不能出现的字符。
    public static String titleOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名表格";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.trim();
        return name.isEmpty() ? "未命名表格" : name;
    }

    private static boolean isPlainNumber(String value) {
        if (!PLAIN_NUMBER.matcher(value).matches()) {
            return false;
        }
        String digits = value.replace("+", "").replace("-", "");
        return !digits.startsWith("0") || digits.startsWith("0.") || "0".equals(digits);
    }

    private static String textOf(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            String value = FORMATTER.formatCellValue(cell, evaluator);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> dedupe(List<String> columns) {
        List<String> out = new ArrayList<>(columns.size());
        for (String column : columns) {
            String name = column;
            int n = 2;
            while (out.contains(name)) {
                name = column + " (" + n + ")";
                n++;
            }
            out.add(name);
        }
        return out;
    }
}
