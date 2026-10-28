package com.xingchen.huibiao.web;

import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.service.bridge.BridgeAgentService;
import com.xingchen.huibiao.service.bridge.BridgeStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体模式：整个数据集丢进来，说一句业务目标，智能体自己规划、执行、出表。
 *
 * zip 和文件夹两种入口最终都变成一组 MultipartFile，后端不关心来源，
 * 统一解压到 data/bridge/datasets/&lt;id&gt;/files 再扫描。
 */
@RestController
@RequestMapping("/api/bridge")
public class BridgeController {

    private final BridgeAgentService agents;
    private final BridgeStore store;

    public BridgeController(BridgeAgentService agents, BridgeStore store) {
        this.agents = agents;
        this.store = store;
    }

    /** 上传压缩包或文件夹里的 Excel，落盘后立刻扫描出一张张表 */
    @PostMapping("/datasets")
    public BridgeDataset create(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                @RequestParam(value = "zip", required = false) MultipartFile zip,
                                @RequestParam(value = "name", required = false) String name,
                                @RequestParam(value = "source", defaultValue = "files") String source) {
        if (files == null) files = new java.util.ArrayList<>();
        if (zip != null && !zip.isEmpty()) files.add(zip);
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先选择压缩包或者文件夹");
        }
        return agents.create(name, source, files);
    }

    @GetMapping("/datasets")
    public List<BridgeDataset> recent() {
        return agents.recent();
    }

    @GetMapping("/datasets/{id}")
    public BridgeDataset detail(@PathVariable("id") String id) {
        return agents.get(id);
    }

    /** 自然语言业务指令 → 规划方案（只规划，不执行） */
    @PostMapping("/datasets/{id}/plan")
    public BridgeDataset plan(@PathVariable("id") String id,
                              @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        String text = instruction == null ? "" : String.valueOf(instruction);
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先用一句话说说这单要做什么");
        }
        return agents.plan(id, text);
    }

    /** 用户确认后执行：跨表关联、计算、异常隔离、标准化导出 */
    @PostMapping("/datasets/{id}/run")
    public BridgeDataset run(@PathVariable("id") String id) {
        return agents.run(id);
    }

    /** 结果和异常清单分页预览 */
    @GetMapping("/datasets/{id}/rows")
    public Map<String, Object> rows(@PathVariable("id") String id,
                                    @RequestParam(value = "kind", defaultValue = "ok") String kind,
                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                    @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return agents.rows(id, kind, page, pageSize);
    }

    @GetMapping("/datasets/{id}/download/{artifact}")
    public ResponseEntity<Resource> download(@PathVariable("id") String id,
                                             @PathVariable("artifact") String artifact) {
        Path path = agents.artifact(id, artifact);
        String encoded = URLEncoder.encode(path.getFileName().toString(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }

    /* ==================== 字段匹配规则：自定义 + 跨数据集复用 ==================== */

    @GetMapping("/rules")
    public List<Map<String, String>> rules() {
        return store.rules();
    }

    @PostMapping("/rules")
    public List<Map<String, String>> addRule(@RequestBody Map<String, String> body) {
        String standard = body.get("standard");
        String alias = body.get("alias");
        if (standard == null || standard.isBlank() || alias == null || alias.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准字段和原始列名都要填");
        }
        return store.addRule(standard.trim(), alias.trim());
    }

    @DeleteMapping("/rules")
    public List<Map<String, String>> removeRule(@RequestParam("alias") String alias) {
        return store.removeRule(alias);
    }

    /** 标准字段清单，前端做下拉框用 */
    @GetMapping("/standard-fields")
    public List<Map<String, String>> standardFields() {
        List<Map<String, String>> list = new java.util.ArrayList<>();
        for (String[] pair : STANDARD_FIELDS) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", pair[0]);
            item.put("label", pair[1]);
            list.add(item);
        }
        return list;
    }

    private static final String[][] STANDARD_FIELDS = {
            {"emp_no", "工号"}, {"name", "姓名"}, {"department", "部门"}, {"position", "职位"},
            {"onboard_date", "入职日期"}, {"status", "在职状态"}, {"base_salary", "基本工资"},
            {"performance", "绩效"}, {"allowance", "补贴"}, {"deduction", "扣款"},
            {"gross_salary", "应发工资"}, {"net_salary", "实发工资"}, {"overtime_hours", "加班小时"},
            {"leave_type", "请假类型"}, {"start_date", "开始日期"}, {"end_date", "结束日期"},
            {"days", "天数"}, {"hours", "请假小时"}, {"annual_left", "年假余额"}, {"amount", "金额"}
    };
}
