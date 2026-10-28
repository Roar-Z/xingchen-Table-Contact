package com.xingchen.huibiao.service.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.util.TimeUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志：只追加、不改写。
 *
 * 没有账号体系时，“谁操作的”取网关/前端透传的 X-Operator，缺省记来源 IP；
 * 这样以后接统一身份，只需要让网关注入这个头，日志格式不用改。
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int MAX_READ = 200;
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private final DataPaths paths;
    private final ObjectMapper mapper;

    public AuditLog(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    public void record(String action, String target, String detail) {
        record(action, target, detail, Map.of());
    }

    public void record(String action, String target, String detail, Map<String, Object> extra) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", TimeUtils.nowSecond());
        entry.put("action", action == null ? "" : action);
        entry.put("target", clip(target, 120));
        entry.put("detail", clip(detail, 400));
        entry.put("actor", actor());
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (key != null && value != null) {
                    entry.put(key, value);
                }
            });
        }
        append(entry);
    }

    public List<Map<String, Object>> recent(int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_READ);
        Path file = paths.auditLogFile();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && out.size() < size; i--) {
                String line = lines.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    out.add(mapper.readValue(line, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    }));
                } catch (Exception ignored) {
                    // 单行损坏不影响整体查询
                }
            }
            return out;
        } catch (IOException e) {
            log.warn("读取审计日志失败：{}", e.getMessage());
            return List.of();
        }
    }

    private void append(Map<String, Object> entry) {
        Path file = paths.auditLogFile();
        try {
            String line = mapper.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            trim(file);
        } catch (IOException e) {
            log.warn("写审计日志失败：{}", e.getMessage());
        }
    }

    /** 文件过大时只保留最近 2000 行，避免长期运行把磁盘写满。 */
    private void trim(Path file) throws IOException {
        if (Files.size(file) <= MAX_BYTES) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int keep = Math.min(lines.size(), 2000);
        List<String> tail = lines.subList(lines.size() - keep, lines.size());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, tail, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private String actor() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "本机";
        }
        HttpServletRequest request = attrs.getRequest();
        if (request == null) {
            return "本机";
        }
        String header = request.getHeader("X-Operator");
        if (header != null && !header.isBlank()) {
            return clip(header, 60);
        }
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "本机" : clip(address, 60);
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
