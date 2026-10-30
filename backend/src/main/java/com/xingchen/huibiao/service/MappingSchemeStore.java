package com.xingchen.huibiao.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.config.SqliteDb;
import com.xingchen.huibiao.dto.ApiRequests.MappingSchemeEntry;
import com.xingchen.huibiao.dto.ApiRequests.MappingSchemeRequest;
import com.xingchen.huibiao.model.MappingScheme;
import com.xingchen.huibiao.util.TextUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// 映射方案仓库：整体读写的小数据，落在 SQLite 的 kv 表（scope = mapping_schemes）。
@Service
public class MappingSchemeStore {

    private static final Logger log = LoggerFactory.getLogger(MappingSchemeStore.class);
    private static final int MAX_SCHEMES = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    // kv 表里的分区名，和别的整体读写数据分开
    private static final String SCOPE = "mapping_schemes";

    private final DataPaths paths;
    private final SqliteDb db;
    private final ObjectMapper mapper;
    private final List<MappingScheme> schemes = new ArrayList<>();

    public MappingSchemeStore(DataPaths paths, SqliteDb db, ObjectMapper mapper) {
        this.paths = paths;
        this.db = db;
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void load() {
        migrateLegacy();
        schemes.clear();
        String body = db.kv(SCOPE);
        if (body != null && !body.isBlank()) {
            try {
                List<MappingScheme> loaded = mapper.readValue(body, new TypeReference<List<MappingScheme>>() {
                });
                if (loaded != null) {
                    schemes.addAll(loaded);
                }
            } catch (Exception e) {
                log.warn("映射方案读取失败，先启动空仓库：{}", e.getMessage());
            }
        }
        log.info("映射方案已加载 {} 个", schemes.size());
    }

    // 老版本的 mapping-schemes.json 只在库里没有这份数据时导入一次，导完改名留档。
    private void migrateLegacy() {
        Path file = paths.mappingSchemesFile();
        if (db.kv(SCOPE) != null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<MappingScheme> loaded = mapper.readValue(Files.readString(file),
                    new TypeReference<List<MappingScheme>>() {
                    });
            if (loaded == null) {
                loaded = new ArrayList<>();
            }
            db.kvPut(SCOPE, loaded);
            log.info("已把 {} 个旧映射方案导入数据库", loaded.size());
            db.retireLegacy(file);
        } catch (Exception e) {
            log.warn("旧映射方案导入失败，先按空仓库启动：{}", e.getMessage());
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

    // 一次原子写入：写坏了不会留下半份方案列表。
    private void write() {
        db.kvPut(SCOPE, schemes);
    }
}
