package com.xingchen.huibiao.web;

import com.xingchen.huibiao.dto.ApiRequests.AnalyzeRequest;
import com.xingchen.huibiao.dto.ApiRequests.CreateTaskRequest;
import com.xingchen.huibiao.dto.ApiRequests.DeleteTasksRequest;
import com.xingchen.huibiao.dto.ApiRequests.PreviewRequest;
import com.xingchen.huibiao.dto.ApiRequests.ReviseRequest;
import com.xingchen.huibiao.model.IntentResult;
import com.xingchen.huibiao.model.TaskRecord;
import com.xingchen.huibiao.service.ResultRowsService;
import com.xingchen.huibiao.service.TaskService;
import com.xingchen.huibiao.service.governance.AuditLog;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService tasks;
    private final ResultRowsService resultRows;
    private final AuditLog audit;

    public TaskController(TaskService tasks, ResultRowsService resultRows, AuditLog audit) {
        this.tasks = tasks;
        this.resultRows = resultRows;
        this.audit = audit;
    }

    @PostMapping("/analyze")
    public IntentResult analyze(@RequestBody AnalyzeRequest request) {
        return tasks.analyze(request);
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody PreviewRequest request) {
        return tasks.preview(request);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "q", required = false) String keyword,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return tasks.list(status, keyword, page, pageSize);
    }

    @PostMapping
    public TaskRecord create(@RequestBody CreateTaskRequest request) {
        TaskRecord task = tasks.create(request);
        audit.record("task.create", task.id, "创建任务「" + task.name + "」，场景 "
                + (task.sceneName == null ? "待识别" : task.sceneName));
        return task;
    }

    @GetMapping("/{id}")
    public TaskRecord detail(@PathVariable("id") String id) {
        return tasks.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个任务"));
    }

    // 结果表分页 + 模糊查询。直接读任务的导出文件，和下载到的 Excel 是同一份数据。
    @GetMapping("/{id}/rows")
    public Map<String, Object> rows(@PathVariable("id") String id,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "10") int pageSize,
                                    @RequestParam(value = "q", required = false) String keyword,
                                    @RequestParam(value = "column", required = false) String column) {
        return resultRows.page(id, page, pageSize, keyword, column);
    }

    @PostMapping("/{id}/review")
    public TaskRecord review(@PathVariable("id") String id) {
        TaskRecord task = tasks.confirmReview(id);
        audit.record("task.review", id, "人工确认方案「" + task.name + "」，进入执行");
        return task;
    }

    @PostMapping("/{id}/rerun")
    public TaskRecord rerun(@PathVariable("id") String id) {
        TaskRecord task = tasks.rerun(id);
        audit.record("task.rerun", id, "按当前配置重跑「" + task.name + "」");
        return task;
    }

    // 用一句话改结果表，例如「第 3 行开始时间改成 09:00」「姓名为张三的行删掉」。
    @PostMapping("/{id}/revise")
    public Map<String, Object> revise(@PathVariable("id") String id,
                                      @RequestBody ReviseRequest request) {
        if (request == null || request.instruction() == null || request.instruction().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先说说结果要怎么改");
        }
        Map<String, Object> result = tasks.revise(id, request.instruction());
        audit.record("task.revise", id, "一句话改结果：" + request.instruction());
        return result;
    }

    // 撤销最近一条自然语言修改，按撤销后的结果重新导出。
    @PostMapping("/{id}/undo-edit")
    public TaskRecord undoEdit(@PathVariable("id") String id) {
        TaskRecord task = tasks.undoRevision(id);
        audit.record("task.undo-edit", id, "撤销最近一条自然语言修改");
        return task;
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody(required = false) DeleteTasksRequest request) {
        int deleted = tasks.delete(request == null ? null : request.ids());
        audit.record("task.delete", request == null || request.ids() == null ? "全部" : String.join(",", request.ids()),
                "删除 " + deleted + " 个任务记录");
        return Map.of("deleted", deleted);
    }

    @GetMapping("/{id}/download/{artifact}")
    public ResponseEntity<Resource> download(@PathVariable("id") String id,
                                             @PathVariable("artifact") String artifact) {
        Path path = tasks.artifactPath(id, artifact);
        String encoded = URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }
}
