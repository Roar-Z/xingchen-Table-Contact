package com.xingchen.huibiao.service;

import com.xingchen.huibiao.config.AppProperties;
import com.xingchen.huibiao.dto.ApiRequests.CreateTaskRequest;
import com.xingchen.huibiao.dto.ApiRequests.FileRef;
import com.xingchen.huibiao.dto.ApiRequests.TaskConfig;
import com.xingchen.huibiao.model.FileMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 示例数据来自比赛提供的四张表。示例走的是和用户上传完全一样的登记、解析、执行流程，
 * 不是前端写死的假数据，所以现场点“示例”能真出一份 HR 导入表。
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    /**
     * sources 是源表，template 是比赛样例里自带的 HR 导入模板。
     * 样例和手工上传走同一套逻辑：导出列一律由模板决定。
     */
    public record Prepared(String name, String brief, List<FileMeta> sources, FileMeta template) {
        public List<FileRef> refs() {
            List<FileRef> refs = new ArrayList<>();
            for (FileMeta meta : sources) {
                refs.add(new FileRef(meta.fileId, meta.role));
            }
            return refs;
        }
    }

    private final AppProperties properties;
    private final FileService files;
    private final TaskService tasks;
    private final TaskStore store;

    public DemoService(AppProperties properties, FileService files, TaskService tasks, TaskStore store) {
        this.properties = properties;
        this.files = files;
        this.tasks = tasks;
        this.store = store;
    }

    public Prepared prepare(String preset) {
        Path dir = sampleDir();
        if ("overtime".equalsIgnoreCase(preset)) {
            FileMeta overtime = register(dir, name -> name.startsWith("ERP") && name.contains("加班"), "overtime",
                    "ERP 导出的加班表");
            FileMeta template = register(dir, name -> name.contains("导入HR") && name.contains("加班"),
                    "target_template", "HR 加班导入模板");
            return new Prepared("总装车间 4 月加班汇总",
                    "筛选总装车间 2026 年 4 月 1 日到 4 月 30 日的加班记录，按 HR 加班导入模板输出，"
                            + "申请加班为空或者不是数字的行放进异常日志。",
                    List.of(overtime), template);
        }
        FileMeta leave = register(dir, name -> name.contains("请假单"), "leave_request", "OA 导出的请假单");
        FileMeta annual = register(dir, name -> name.contains("年假"), "annual_balance", "年假余额表");
        FileMeta template = register(dir, name -> name.contains("最低版本导入"), "target_template",
                "HR 缺勤导入模板");
        return new Prepared("OA 请假单转 HR 缺勤",
                "把 OA 导出的请假单和年假余额表按工号关联，先扣剩余年假，不够的部分自动转成事假，"
                        + "最后按 HR 最低版本缺勤模板导出，工号对不上的行单独记异常。",
                List.of(leave, annual), template);
    }

    public Map<String, Object> prepareMap(String preset) {
        Prepared prepared = prepare(preset);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", prepared.name());
        out.put("brief", prepared.brief());
        out.put("files", prepared.sources());
        out.put("template", prepared.template());
        out.put("template_file_id", prepared.template().fileId);
        return out;
    }

    /**
     * 第一次启动、任务表为空时，跑两份示例，省得评委打开页面看到一片空白。
     * 只有真的读到了样例文件才会创建，不会写死假任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!properties.isSeedDemo() || store.count() > 0) {
            return;
        }
        try {
            Path dir = sampleDir();
            if (!hasFile(dir, name -> name.contains("请假单"))) {
                log.info("示例目录没有样例表，跳过演示数据初始化：{}", dir);
                return;
            }
            Prepared leave = prepare("leave");
            tasks.create(new CreateTaskRequest(leave.name(), leave.brief(), leave.refs(),
                    new TaskConfig("leave", null, leave.template().fileId, true, false)));
            if (hasFile(dir, name -> name.contains("加班"))) {
                Prepared overtime = prepare("overtime");
                tasks.create(new CreateTaskRequest(overtime.name(), overtime.brief(), overtime.refs(),
                        new TaskConfig("overtime", null, overtime.template().fileId, true, false)));
            }
            log.info("已提交示例任务，前端打开就能看到真实处理结果");
        } catch (Exception e) {
            log.warn("演示数据初始化失败，不影响正常使用：{}", e.getMessage());
        }
    }

    private FileMeta register(Path dir, FileFilter filter, String role, String label) {
        Path source = find(dir, filter)
                .orElseThrow(() -> new IllegalStateException("示例目录缺少" + label + "：" + dir));
        return files.registerExisting(source, role);
    }

    private boolean hasFile(Path dir, FileFilter filter) {
        return find(dir, filter).isPresent();
    }

    private Optional<Path> find(Path dir, FileFilter filter) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        String lower = name.toLowerCase(Locale.ROOT);
                        return lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .filter(p -> filter.accept(p.getFileName().toString()))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path sampleDir() {
        List<String> candidates = List.of(
                properties.getSampleDir(),
                "sample_data/raw",
                "../sample_data/raw",
                "../../sample_data/raw");
        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Paths.get(properties.getSampleDir()).toAbsolutePath().normalize();
    }

    private interface FileFilter {
        boolean accept(String name);
    }
}
