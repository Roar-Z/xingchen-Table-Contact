package com.xingchen.huibiao.web;

import com.xingchen.huibiao.model.BridgeDataset;
import com.xingchen.huibiao.service.bridge.BridgeAgentService;
import com.xingchen.huibiao.service.bridge.BridgeKnowledge;
import com.xingchen.huibiao.service.bridge.BridgeMemory;
import com.xingchen.huibiao.service.bridge.BridgeStore;
import com.xingchen.huibiao.service.SettingsService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 智能体模式：整个数据集丢进来，说一句业务目标，智能体自己规划、执行、出表。
@RestController
@RequestMapping("/api/bridge")
public class BridgeController {

    private final BridgeAgentService agents;
    private final BridgeStore store;
    private final BridgeMemory memory;
    private final BridgeKnowledge knowledge;
    private final SettingsService settings;

    public BridgeController(BridgeAgentService agents, BridgeStore store, BridgeMemory memory,
                            BridgeKnowledge knowledge, SettingsService settings) {
        this.agents = agents;
        this.store = store;
        this.memory = memory;
        this.knowledge = knowledge;
        this.settings = settings;
    }

    // 上传压缩包或文件夹里的 Excel，落盘后立刻扫描出一张张表
    @PostMapping("/datasets")
    public BridgeDataset create(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                @RequestParam(value = "zip", required = false) MultipartFile zip,
                                @RequestParam(value = "name", required = false) String name,
                                @RequestParam(value = "source", defaultValue = "files") String source,
                                @RequestParam(value = "access", required = false) String access) {
        if (files == null) files = new java.util.ArrayList<>();
        if (zip != null && !zip.isEmpty()) files.add(zip);
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先选择压缩包或者文件夹");
        }
        return agents.create(name, source, files, access);
    }

    @GetMapping("/datasets")
    public List<BridgeDataset> recent() {
        return agents.recent();
    }

    // 清理最近记录：body 里给 ids 就删这几条，不给（或空数组）就清空全部。
    @PostMapping("/datasets/delete")
    public Map<String, Object> delete(@RequestBody(required = false) Map<String, Object> body) {
        List<String> ids = new ArrayList<>();
        Object raw = body == null ? null : body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) ids.add(String.valueOf(item));
            }
        }
        // 要删的就这几条；清空全部时按现有记录逐条保底，别漏掉还没同步过的结果
        List<String> targets = new ArrayList<>(ids);
        if (targets.isEmpty()) {
            for (BridgeDataset item : agents.recent()) {
                targets.add(item.id);
            }
        }
        int kept = 0;
        for (String id : targets) {
            kept += agents.preserveArtifacts(id);
        }
        int deleted = store.delete(ids);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("kept_files", kept);
        out.put("items", agents.recent());
        return out;
    }

    @DeleteMapping("/datasets/{id}")
    public List<BridgeDataset> deleteOne(@PathVariable("id") String id) {
        agents.preserveArtifacts(id);
        store.deleteOne(id);
        return agents.recent();
    }

    // ---------------- 知识库：用户上传口径文档，规划和问答时按相关度引用 ----------------

    // 上传知识库文件（Word / Excel / PDF / 文本），返回这次收进来的清单和跳过原因
    @PostMapping("/knowledge")
    public Map<String, Object> uploadKnowledge(
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "先选择要上传的文档");
        }
        return knowledge.upload(files);
    }

    // 知识库清单：文件名、类型、字数、切片数、上传时间
    @GetMapping("/knowledge")
    public Map<String, Object> knowledgeList() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", knowledge.list());
        out.put("stats", knowledge.stats());
        return out;
    }

    // 删一份知识：连同原文件和切片一起清掉
    @DeleteMapping("/knowledge/{id}")
    public Map<String, Object> removeKnowledge(@PathVariable("id") String id) {
        if (!knowledge.remove(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这份知识，可能已经被删了");
        }
        return knowledgeList();
    }

    // 试检索：给一句业务目标，看看现在会引用到哪几段。
    @GetMapping("/knowledge/search")
    public List<Map<String, Object>> searchKnowledge(@RequestParam("q") String query) {
        return knowledge.retrieve(query).stream().map(hit -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", hit.file());
            out.put("kind", hit.kind());
            out.put("score", Math.round(hit.score() * 1000) / 10.0);
            out.put("text", hit.text());
            return out;
        }).toList();
    }

    @GetMapping("/datasets/{id}")
    public BridgeDataset detail(@PathVariable("id") String id) {
        return agents.get(id);
    }

    // 一句话业务目标 → 规划方案（只规划，不执行）；不给目标时由智能体按数据结构自己推断
    @PostMapping("/datasets/{id}/plan")
    public BridgeDataset plan(@PathVariable("id") String id,
                              @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        return agents.plan(id, instruction == null ? "" : String.valueOf(instruction));
    }

    // 全自主跑一单：自己定目标（没给就按结构推断）→ 规划 → 执行 → 对照验收标准 →
    @PostMapping("/datasets/{id}/auto")
    public BridgeDataset auto(@PathVariable("id") String id,
                              @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        return agents.auto(id, instruction == null ? "" : String.valueOf(instruction));
    }

    // 老入口：同样走自主循环，目标取数据集上保存的那句
    @PostMapping("/datasets/{id}/run")
    public BridgeDataset run(@PathVariable("id") String id) {
        return agents.run(id);
    }

    // 真实进度：前端每 0.7 秒带上 since 轮询，拿到的是后端真实写下的每一步
    @GetMapping("/datasets/{id}/events")
    public Map<String, Object> events(@PathVariable("id") String id,
                                      @RequestParam(value = "since", defaultValue = "0") int since) {
        return agents.events(id, since);
    }

    // 取消这一单：不中断线程，只置取消标记。执行侧在下一个检查点停下，
    @PostMapping("/datasets/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable("id") String id) {
        return agents.cancel(id);
    }

    // 可执行权限：manual 手动审批（方案出完停住，等人确认）、auto 自动审批（默认，直接跑）、
    @PostMapping("/datasets/{id}/access")
    public BridgeDataset access(@PathVariable("id") String id,
                                @RequestBody(required = false) Map<String, Object> body) {
        Object access = body == null ? null : body.get("access");
        return agents.access(id, access == null ? "" : String.valueOf(access));
    }

    // 手动审批的「确认执行」：方案已经在了，点完直接开跑，不重新规划
    @PostMapping("/datasets/{id}/approve")
    public BridgeDataset approve(@PathVariable("id") String id) {
        return agents.approve(id);
    }

    // 会话式追问：body 的 instruction 就是那句话。
    @PostMapping("/datasets/{id}/chat")
    public BridgeDataset chat(@PathVariable("id") String id,
                              @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        return agents.chat(id, instruction == null ? "" : String.valueOf(instruction));
    }

    // 结果和异常清单分页预览
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

    // 交付后用一句话改：body 的 instruction 就是用户那句自然语言，
    @PostMapping("/datasets/{id}/tweak")
    public BridgeDataset tweak(@PathVariable("id") String id,
                               @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        return agents.tweak(id, instruction == null ? "" : String.valueOf(instruction));
    }

    // 选目录用：不给 path 就从默认导出目录和磁盘根开始列，给了就列它的子目录
    @GetMapping("/export-dir/browse")
    public Map<String, Object> browse(@RequestParam(value = "path", required = false) String path) {
        return agents.browse(path);
    }

    // 选择器里的「新建文件夹」：body 给 path（在哪个目录下建）和 name（新文件夹名）
    @PostMapping("/export-dir/mkdir")
    public Map<String, Object> mkdir(@RequestBody(required = false) Map<String, Object> body) {
        Object path = body == null ? null : body.get("path");
        Object name = body == null ? null : body.get("name");
        return agents.mkdir(path == null ? "" : String.valueOf(path),
                name == null ? "" : String.valueOf(name));
    }

    // 选择器里右键「重命名」：body 给 path（要改的文件夹）和 name（新名字）
    @PostMapping("/export-dir/rename")
    public Map<String, Object> rename(@RequestBody(required = false) Map<String, Object> body) {
        Object path = body == null ? null : body.get("path");
        Object name = body == null ? null : body.get("name");
        return agents.rename(path == null ? "" : String.valueOf(path),
                name == null ? "" : String.valueOf(name));
    }

    // 导出位置：custom 是用户自定义的根目录（空表示用默认），
    @GetMapping("/export-dir")
    public Map<String, Object> exportDir() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("custom", settings.exportDir());
        out.put("default_dir", agents.defaultExportRoot());
        out.put("effective_dir", agents.exportRoot());
        return out;
    }

    // 设置导出位置：dir 传空字符串表示恢复默认。
    @PostMapping("/export-dir")
    public Map<String, Object> setExportDir(@RequestBody(required = false) Map<String, Object> body) {
        Object dir = body == null ? null : body.get("dir");
        settings.setExportDir(dir == null ? "" : String.valueOf(dir));
        Object datasetId = body == null ? null : body.get("dataset_id");
        Map<String, Object> moved = datasetId == null || String.valueOf(datasetId).isBlank()
                ? null : agents.relocate(String.valueOf(datasetId));
        Map<String, Object> out = exportDir();
        out.put("moved", moved != null && Boolean.TRUE.equals(moved.get("moved")));
        out.put("moved_dir", moved == null ? "" : moved.get("dir"));
        out.put("error", moved == null ? "" : moved.get("error"));
        return out;
    }

    // 打开导出文件所在文件夹：body 里给 artifact 就定位到该文件，不给就打开整个 downloads 目录。
    @PostMapping("/datasets/{id}/reveal")
    public Map<String, Object> reveal(@PathVariable("id") String id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Object artifact = body == null ? null : body.get("artifact");
        return agents.reveal(id, artifact == null ? "" : String.valueOf(artifact));
    }

    // ==================== 字段匹配规则：自定义 + 跨数据集复用 ====================

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

    // 智能体模式的经验记忆：跑成功的同类数据集会沉淀一条，规划时优先复用
    @GetMapping("/experiences")
    public List<Map<String, Object>> experiences() {
        return memory.summary();
    }

    @DeleteMapping("/experiences")
    public List<Map<String, Object>> removeExperience(@RequestParam("id") String id) {
        memory.remove(id);
        return memory.summary();
    }

    // 人工给人经验打分：「有用」加可信度，「不适用」减可信度。
    @PostMapping("/experiences/{id}/feedback")
    public List<Map<String, Object>> feedback(@PathVariable("id") String id,
                                             @RequestBody Map<String, Object> body) {
        Object useful = body == null ? null : body.get("useful");
        boolean ok = useful instanceof Boolean value ? value : Boolean.parseBoolean(String.valueOf(useful));
        memory.feedback(id, ok);
        return memory.summary();
    }

    // 标准字段清单，前端做下拉框用
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
