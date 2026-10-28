package com.xingchen.huibiao.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.config.DataPaths;
import com.xingchen.huibiao.model.TaskRecord;
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
import java.util.List;
import java.util.Optional;

/**
 * 任务仓库。没有上数据库，比赛现场拷贝 data 目录就能把记录带走。
 * 写文件用临时文件再 rename，避免程序被中断时 tasks.json 只剩半截。
 */
@Service
public class TaskStore {

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);

    private final DataPaths paths;
    private final ObjectMapper mapper;
    private final List<TaskRecord> tasks = new ArrayList<>();

    public TaskStore(DataPaths paths, ObjectMapper mapper) {
        this.paths = paths;
        this.mapper = mapper;
    }

    @PostConstruct
    public synchronized void load() {
        Path file = paths.tasksFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<TaskRecord> loaded = mapper.readValue(file.toFile(), new TypeReference<>() {
            });
            tasks.clear();
            tasks.addAll(loaded);
        } catch (IOException e) {
            log.warn("任务记录读取失败，先启动空仓库：{}", e.getMessage());
        }
    }

    public synchronized void save() {
        Path file = paths.tasksFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), tasks);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("保存任务记录失败：" + e.getMessage(), e);
        }
    }

    public synchronized List<TaskRecord> all() {
        List<TaskRecord> copy = new ArrayList<>(tasks);
        copy.sort(Comparator.comparing((TaskRecord t) -> t.createdAt == null ? "" : t.createdAt).reversed());
        return copy;
    }

    public synchronized Optional<TaskRecord> find(String id) {
        return tasks.stream().filter(t -> id.equals(t.id)).findFirst();
    }

    public synchronized void put(TaskRecord task) {
        tasks.removeIf(t -> t.id.equals(task.id));
        tasks.add(task);
        save();
    }

    /**
     * 批量删除，只写一次文件。返回真正删掉的条数。
     */
    public synchronized int removeAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int before = tasks.size();
        tasks.removeIf(t -> ids.contains(t.id));
        int removed = before - tasks.size();
        if (removed > 0) {
            save();
        }
        return removed;
    }

    public synchronized String nextId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "T" + date + "-";
        int max = 0;
        for (TaskRecord task : tasks) {
            if (task.id != null && task.id.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(task.id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 编号被手动改过就不参与计数
                }
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    public synchronized int count() {
        return tasks.size();
    }
}
