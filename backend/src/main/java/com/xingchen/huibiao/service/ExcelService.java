package com.xingchen.huibiao.service;

import com.xingchen.huibiao.model.FileMeta;
import com.xingchen.huibiao.util.TextUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 表格读写。解析时保留源表行号，导出时按目标系统的习惯写表头和列宽。
@Service
public class ExcelService {

    private static final Logger log = LoggerFactory.getLogger(ExcelService.class);

    public record RowData(int rowNumber, Map<String, String> values, String raw) {
    }

    public record SheetData(String sheetName, int headerRow, List<String> headers, List<RowData> rows,
                            List<FileMeta.SheetInfo> sheets) {
    }

    public SheetData read(Path path, HeaderCatalog catalog, Map<String, List<String>> aliases, boolean autoHeader) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            return readCsv(path, catalog, aliases, autoHeader);
        }
        try (InputStream in = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(in)) {
            return readWorkbook(workbook, catalog, aliases, autoHeader);
        } catch (Exception e) {
            throw new IllegalStateException("读取表格失败：" + path.getFileName() + "，" + e.getMessage(), e);
        }
    }

    private SheetData readWorkbook(Workbook workbook, HeaderCatalog catalog,
                                   Map<String, List<String>> aliases, boolean autoHeader) {
        List<FileMeta.SheetInfo> summaries = new ArrayList<>();
        SheetData first = null;
        DataFormatter formatter = new DataFormatter(Locale.US);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            List<List<String>> rawRows = new ArrayList<>();
            int maxCols = 0;
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    rawRows.add(new ArrayList<>());
                    continue;
                }
                int last = Math.max(0, row.getLastCellNum());
                maxCols = Math.max(maxCols, last);
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    String value;
                    try {
                        value = formatter.formatCellValue(cell, evaluator);
                    } catch (Exception e) {
                        value = "";
                    }
                    cells.add(value == null ? "" : value.trim());
                }
                rawRows.add(cells);
            }
            summaries.add(new FileMeta.SheetInfo(sheet.getSheetName(), Math.max(0, sheet.getLastRowNum()), maxCols));
            SheetData parsed = buildSheet(sheet.getSheetName(), rawRows, maxCols, catalog, aliases,
                    autoHeader, sheet.getFirstRowNum());
            if (first == null && !parsed.rows().isEmpty()) {
                first = parsed;
            }
        }
        if (first == null) {
            first = new SheetData(workbook.getSheetName(0), 0, List.of(), List.of(), summaries);
        }
        return new SheetData(first.sheetName(), first.headerRow(), first.headers(), first.rows(), summaries);
    }

    private SheetData readCsv(Path path, HeaderCatalog catalog, Map<String, List<String>> aliases, boolean autoHeader) {
        List<List<String>> rawRows = new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(path);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.contains("\uFFFD")) {
                text = new String(bytes, Charset.forName("GBK"));
            }
            text = text.replace("\uFEFF", "");
            for (String line : text.split("\\r?\\n")) {
                rawRows.add(parseCsvLine(line));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 CSV 失败：" + e.getMessage(), e);
        }
        int maxCols = rawRows.stream().mapToInt(List::size).max().orElse(0);
        SheetData data = buildSheet("CSV", rawRows, maxCols, catalog, aliases, autoHeader, 0);
        List<FileMeta.SheetInfo> summaries = List.of(new FileMeta.SheetInfo("CSV", rawRows.size(), maxCols));
        return new SheetData(data.sheetName(), data.headerRow(), data.headers(), data.rows(), summaries);
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private SheetData buildSheet(String sheetName, List<List<String>> rawRows, int maxCols,
                                 HeaderCatalog catalog, Map<String, List<String>> aliases,
                                 boolean autoHeader, int firstRowNum) {
        if (rawRows.isEmpty()) {
            return new SheetData(sheetName, 0, List.of(), List.of(), List.of());
        }
        int headerIndex = autoHeader ? detectHeaderIndex(rawRows, catalog, aliases) : 0;

        List<String> headers = new ArrayList<>();
        List<String> used = new ArrayList<>();
        // 保留的原始列下标：表头和整列都空着的列直接丢掉，避免出现“列2…列18”这种大片空白
        List<Integer> keep = new ArrayList<>();
        List<String> headerRow = rawRows.get(headerIndex);
        int width = Math.max(maxCols, headerRow.size());
        for (int c = 0; c < width; c++) {
            String base = c < headerRow.size() ? cleanHeader(headerRow.get(c)) : "";
            if (base.isEmpty() && !hasValueBelow(rawRows, headerIndex, c)) {
                continue;
            }
            if (base.isEmpty()) {
                base = "列" + (c + 1);
            }
            String name = base;
            int n = 2;
            while (used.contains(name)) {
                name = base + " (" + n + ")";
                n++;
            }
            used.add(name);
            headers.add(name);
            keep.add(c);
        }

        List<RowData> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < rawRows.size(); i++) {
            List<String> cells = rawRows.get(i);
            Map<String, String> values = new LinkedHashMap<>();
            boolean any = false;
            for (int h = 0; h < keep.size(); h++) {
                int c = keep.get(h);
                String value = c < cells.size() ? cells.get(c) : "";
                values.put(headers.get(h), value);
                if (!TextUtils.isBlank(value)) {
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            String raw = cells.size() > 4 ? String.join(" / ", cells.subList(0, 4)) : String.join(" / ", cells);
            rows.add(new RowData(firstRowNum + i + 1, values, raw));
        }
        return new SheetData(sheetName, headerIndex, headers, rows, List.of());
    }

    // 找表头行。先按业务别名打分；一张任意表（食堂价格表、设备台账之类）命中不了别名时，
    private int detectHeaderIndex(List<List<String>> rawRows, HeaderCatalog catalog,
                                  Map<String, List<String>> aliases) {
        int limit = Math.min(rawRows.size(), 12);
        int bestScore = 0;
        int bestIndex = -1;
        for (int i = 0; i < limit; i++) {
            int score = catalog.scoreRow(rawRows.get(i), aliases);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        if (bestScore > 0) {
            return Math.max(bestIndex, 0);
        }
        int maxFilled = 0;
        for (int i = 0; i < limit; i++) {
            maxFilled = Math.max(maxFilled, filledCount(rawRows.get(i)));
        }
        if (maxFilled < 2) {
            return 0;
        }
        int threshold = Math.max(2, (int) Math.ceil(maxFilled * 0.6));
        for (int i = 0; i < limit; i++) {
            if (filledCount(rawRows.get(i)) >= threshold) {
                return i;
            }
        }
        return 0;
    }

    private int filledCount(List<String> row) {
        int count = 0;
        for (String cell : row) {
            if (!TextUtils.isBlank(cell)) {
                count++;
            }
        }
        return count;
    }

    // 表头单元格去掉换行和多余空格，多行表头在页面上会撑得很高。
    private String cleanHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", " ").trim();
    }

    private boolean hasValueBelow(List<List<String>> rawRows, int headerIndex, int column) {
        for (int i = headerIndex + 1; i < rawRows.size(); i++) {
            List<String> cells = rawRows.get(i);
            if (column < cells.size() && !TextUtils.isBlank(cells.get(column))) {
                return true;
            }
        }
        return false;
    }

    // 写 .xls，给只认 HSSF 的老 HR 系统用。
    public void writeXls(Path path, List<String> headers, List<List<Object>> rows) throws IOException {
        try (Workbook workbook = new HSSFWorkbook()) {
            writeWorkbook(workbook, path, headers, rows);
        }
    }

    // 写 .xlsx，给人工复核和存档用。
    public void writeXlsx(Path path, List<String> headers, List<List<Object>> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            writeWorkbook(workbook, path, headers, rows);
        }
    }

    private void writeWorkbook(Workbook workbook, Path path, List<String> headers, List<List<Object>> rows)
            throws IOException {
        Sheet sheet = workbook.createSheet("Sheet1");
        CellStyle headStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        headStyle.setFont(font);

        Row head = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = head.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(headStyle);
        }
        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r + 1);
            List<Object> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                Cell cell = row.createCell(c);
                Object value = values.get(c);
                if (value == null) {
                    cell.setBlank();
                } else if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else {
                    cell.setCellValue(String.valueOf(value));
                }
            }
        }
        for (int c = 0; c < headers.size(); c++) {
            int width = Math.min(38, Math.max(10, headers.get(c).length() * 2 + 4));
            sheet.setColumnWidth(c, width * 256);
        }
        sheet.createFreezePane(0, 1);
        try (OutputStream out = Files.newOutputStream(path)) {
            workbook.write(out);
        }
        log.info("写出文件 {}（{} 行）", path.getFileName(), rows.size());
    }

    public List<List<Object>> toCells(List<String> headers, List<Map<String, String>> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            List<Object> line = new ArrayList<>();
            for (String header : headers) {
                line.add(row.get(header));
            }
            result.add(line);
        }
        return result;
    }

    public List<String> headersOf(List<Map<String, String>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.get(0).keySet());
    }
}
