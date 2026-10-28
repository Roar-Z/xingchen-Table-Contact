package com.xingchen.huibiao.web;

import com.xingchen.huibiao.service.sheet.SheetService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 表格智作的接口。前端只对接这一组，命名和任务那边保持一致（分页、搜索、下载）。
 */
@RestController
@RequestMapping("/api/sheets")
public class SheetController {

    private final SheetService sheets;

    public SheetController(SheetService sheets) {
        this.sheets = sheets;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "q", required = false) String keyword) {
        return sheets.list(keyword);
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return sheets.templates();
    }

    @GetMapping("/logs")
    public List<Map<String, Object>> logs(@RequestParam(value = "limit", defaultValue = "60") int limit) {
        return sheets.recentLogs(Math.max(1, Math.min(limit, 300)));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable("id") String id) {
        return sheets.detail(id);
    }

    @GetMapping("/{id}/rows")
    public Map<String, Object> rows(@PathVariable("id") String id,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
                                    @RequestParam(value = "q", required = false) String keyword,
                                    @RequestParam(value = "column", required = false) String column) {
        return sheets.page(id, page, pageSize, keyword, column);
    }

    @PostMapping("/upload")
    public List<Map<String, Object>> upload(@RequestParam("files") MultipartFile[] files) {
        return sheets.upload(files);
    }

    @PostMapping("/from-template")
    public Map<String, Object> fromTemplate(@RequestBody Map<String, String> body) {
        return sheets.fromTemplate(body.get("template_id"), body.get("name"));
    }

    @PostMapping("/blank")
    public Map<String, Object> blank(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> columns = body.get("columns") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        return sheets.blank(body.get("name") == null ? null : String.valueOf(body.get("name")), columns);
    }

    /**
     * 一句话建表或多表融合。sheet_ids 传了就只在这几张表之间做。
     */
    @PostMapping("/compose")
    public Map<String, Object> compose(@RequestBody Map<String, Object> body) {
        String instruction = body.get("instruction") == null ? "" : String.valueOf(body.get("instruction"));
        List<String> ids = body.get("sheet_ids") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        return sheets.compose(instruction, ids);
    }

    @PostMapping("/{id}/revise")
    public Map<String, Object> revise(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        return sheets.revise(id, body.get("instruction"));
    }

    @PostMapping("/{id}/cell")
    public Map<String, Object> cell(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        int row = body.get("row") == null ? 0 : Integer.parseInt(String.valueOf(body.get("row")));
        String column = body.get("column") == null ? "" : String.valueOf(body.get("column"));
        String value = body.get("value") == null ? "" : String.valueOf(body.get("value"));
        return sheets.setCell(id, row, column, value);
    }

    @PostMapping("/{id}/rows")
    public Map<String, Object> addRow(@PathVariable("id") String id) {
        return sheets.addRow(id);
    }

    @DeleteMapping("/{id}/rows/{row}")
    public Map<String, Object> deleteRow(@PathVariable("id") String id, @PathVariable("row") int row) {
        return sheets.deleteRow(id, row);
    }

    @PostMapping("/{id}/columns")
    public Map<String, Object> addColumn(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        return sheets.addColumn(id, body.get("name"), body.get("value"));
    }

    @PostMapping("/{id}/rename")
    public Map<String, Object> rename(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        return sheets.rename(id, body.get("name"));
    }

    @PostMapping("/{id}/undo")
    public Map<String, Object> undo(@PathVariable("id") String id) {
        return sheets.undo(id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable("id") String id) {
        Map<String, Object> detail = sheets.detail(id);
        Path file = sheets.fileOf(id);
        String name = String.valueOf(detail.get("name")) + ".xlsx";
        String encoded = UriUtils.encode(name, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        sheets.delete(id);
        return Map.of("deleted", id);
    }
}
