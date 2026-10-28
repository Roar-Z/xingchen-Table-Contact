package com.xingchen.huibiao.service.sheet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.SheetRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 表格仓库：元数据存 data/sheets.json，数据本体存 data/sheets/{id}.xlsx。
 * 没有数据库，拷贝整个 data 目录就能把仓库带到另一台机器。
 */
@Service
public class SheetStore {

    private static final Logger log = LoggerFactory.getLogger(SheetStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private final Map<String, SheetRecord> items = new LinkedHashMap<>();

    public SheetStore(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        Path file = paths.sheetsFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<SheetRecord> list = mapper.readValue(file.toFile(), new TypeReference<List<SheetRecord>>() {
            });
            for (SheetRecord record : list) {
                if (record.id != null) {
                    items.put(record.id, record);
                }
            }
            log.info("已加载 {} 张表格", items.size());
        } catch (IOException e) {
            log.warn("表格索引读取失败，先按空仓库处理：{}", e.getMessage());
        }
    }

    public synchronized List<SheetRecord> all() {
        List<SheetRecord> list = new ArrayList<>(items.values());
        list.sort(Comparator.comparing((SheetRecord r) -> r.updatedAt == null ? "" : r.updatedAt).reversed());
        return list;
    }

    public synchronized Optional<SheetRecord> find(String id) {
        return Optional.ofNullable(items.get(id));
    }

    public SheetRecord require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("没有找到表格 " + id));
    }

    public synchronized String nextId() {
        String prefix = "S" + LocalDate.now().format(DAY) + "-";
        int max = 0;
        for (String id : items.keySet()) {
            if (!id.startsWith(prefix)) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(id.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // 手工改过的 id 不参与编号
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    public synchronized void put(SheetRecord record) {
        items.put(record.id, record);
        save();
    }

    public synchronized void remove(String id) {
        items.remove(id);
        save();
        try {
            Files.deleteIfExists(paths.sheetPath(id));
            Files.deleteIfExists(paths.sheetBackupPath(id));
        } catch (IOException e) {
            log.warn("删除表格文件失败：{}", e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(paths.root());
            Path tmp = paths.root().resolve("sheets.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(items.values()));
            Files.move(tmp, paths.sheetsFile(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存表格索引失败：" + e.getMessage(), e);
        }
    }
}
