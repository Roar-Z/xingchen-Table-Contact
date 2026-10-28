package com.xingchen.huibiao.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.dto.ApiRequests.MappingSchemeEntry;
import com.xingchen.huibiao.dto.ApiRequests.MappingSchemeRequest;
import com.xingchen.huibiao.model.MappingScheme;
import com.xingchen.huibiao.util.TextUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 映射方案仓库。和任务记录一样落在 data 目录，先用内存 + JSON 文件，不引入数据库。
 * 同名方案覆盖保存，最多保留 20 个，避免文件无限增长。
 */
@Service
public class MappingSchemeStore {

    private static final Logger log = LoggerFactory.getLogger(MappingSchemeStore.class);
    private static final int MAX_SCHEMES = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private final List<MappingScheme> schemes = new ArrayList<>();

    public MappingSchemeStore(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void load() {
        Path file = paths.mappingSchemesFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<MappingScheme> loaded = mapper.readValue(file.toFile(), new TypeReference<>() {
            });
            schemes.clear();
            schemes.addAll(loaded);
        } catch (IOException e) {
            log.warn("映射方案读取失败，先启动空仓库：{}", e.getMessage());
        }
    }

    public synchronized List<MappingScheme> all() {
        return new ArrayList<>(schemes);
    }

    public synchronized MappingScheme save(MappingSchemeRequest request) {
        if (request == null || TextUtils.isBlank(request.name())) {
            throw new IllegalArgumentException("先给映射方案起个名字");
        }
        List<MappingSchemeEntry> rows = request.mapping() == null ? List.of() : request.mapping();
        MappingScheme scheme = schemes.stream()
                .filter(item -> request.name().trim().equals(item.name))
                .findFirst()
                .orElse(null);
        if (scheme == null) {
            scheme = new MappingScheme();
        }
        scheme.name = request.name().trim();
        scheme.scene = request.scene() == null ? "" : request.scene();
        scheme.templateName = request.templateName() == null ? "" : request.templateName();
        scheme.savedAt = LocalDateTime.now().format(TIME);
        scheme.mapping = clean(rows);
        if (scheme.mapping.isEmpty()) {
            throw new IllegalArgumentException("方案里还没有可用的映射");
        }
        if (scheme.id == null) {
            scheme.id = nextId();
            schemes.add(0, scheme);
        }
        while (schemes.size() > MAX_SCHEMES) {
            schemes.remove(schemes.size() - 1);
        }
        write();
        return scheme;
    }

    public synchronized boolean remove(String id) {
        if (TextUtils.isBlank(id)) {
            return false;
        }
        boolean removed = schemes.removeIf(item -> id.equals(item.id));
        if (removed) {
            write();
        }
        return removed;
    }

    private List<MappingScheme.Entry> clean(List<MappingSchemeEntry> rows) {
        List<MappingScheme.Entry> list = new ArrayList<>();
        for (MappingSchemeEntry row : rows) {
            if (row == null || TextUtils.isBlank(row.target())) {
                continue;
            }
            String expr = row.expr() == null ? "" : row.expr().trim();
            // 一条映射要么有源列，要么有拼接表达式，两者都没有就不算数
            if (TextUtils.isBlank(row.column()) && TextUtils.isBlank(expr)) {
                continue;
            }
            String fileName = row.fileName() == null ? "" : row.fileName().trim();
            list.add(new MappingScheme.Entry(row.target().trim(), fileName,
                    row.column() == null ? "" : row.column().trim(), expr));
        }
        return list;
    }

    private String nextId() {
        long base = System.currentTimeMillis();
        String id = "M" + base;
        while (containsId(id)) {
            base += 1;
            id = "M" + base;
        }
        return id;
    }

    private boolean containsId(String id) {
        for (MappingScheme item : schemes) {
            if (id.equals(item.id)) {
                return true;
            }
        }
        return false;
    }

    private void write() {
        Path file = paths.mappingSchemesFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), schemes);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存映射方案失败：" + e.getMessage(), e);
        }
    }
}
