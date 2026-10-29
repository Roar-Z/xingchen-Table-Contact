package com.xingchen.huibiao.service.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.util.TimeUtils;
import jakarta.annotation.PostConstruct;
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
    /** 只保留最近 100 条审计记录，超出后丢最旧的；单页最多取 50 条。 */
    static final int MAX_ENTRIES = 100;
    private static final int MAX_READ = MAX_ENTRIES;
    private static final int MAX_PAGE_SIZE = 50;

    private final DataPaths paths;
    private final ObjectMapper mapper;

    public AuditLog(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    /** 启动时把历史遗留的超长日志收敛到上限，避免旧文件一直超出 100 条。 */
    @PostConstruct
    public void init() {
        Path file = paths.auditLogFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            trim(file);
        } catch (IOException e) {
            log.warn("审计日志收敛失败：{}", e.getMessage());
        }
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
        List<Map<String, Object>> all = readAll();
        return new ArrayList<>(all.subList(0, Math.min(size, all.size())));
    }

    /**
     * 审计日志分页：新的在前，返回 items / total / page / pages。
     * 只读当前页，不把整个文件返回给前端。
     */
    public Map<String, Object> page(int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        List<Map<String, Object>> all = readAll();
        int total = all.size();
        int pages = total == 0 ? 1 : (int) Math.ceil(total * 1.0 / size);
        int current = Math.min(Math.max(page, 1), pages);
        int from = Math.min((current - 1) * size, total);
        int to = Math.min(from + size, total);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", new ArrayList<>(all.subList(from, to)));
        out.put("total", total);
        out.put("page", current);
        out.put("page_size", size);
        out.put("pages", pages);
        out.put("keep", MAX_ENTRIES);
        return out;
    }

    /** 从新到旧读出全部留存记录；单行损坏跳过，不影响整体查询。 */
    private List<Map<String, Object>> readAll() {
        Path file = paths.auditLogFile();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && out.size() < MAX_READ; i--) {
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

    /** 每次追加后把文件收敛到最近 MAX_ENTRIES 条，避免长期运行无限增长。 */
    private void trim(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                kept.add(line);
            }
        }
        if (kept.size() <= MAX_ENTRIES) {
            return;
        }
        List<String> tail = kept.subList(kept.size() - MAX_ENTRIES, kept.size());
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
