package com.xingchen.huibiao.web;

import com.xingchen.huibiao.config.AppProperties;
import com.xingchen.huibiao.service.TaskStore;
import com.xingchen.huibiao.util.TimeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SystemController {

    private final AppProperties properties;
    private final TaskStore tasks;

    public SystemController(AppProperties properties, TaskStore tasks) {
        this.properties = properties;
        this.tasks = tasks;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("version", properties.getVersion());
        out.put("time", TimeUtils.nowSecond());
        out.put("llm_enabled", properties.getLlm().isEnabled());
        out.put("parser", properties.getLlm().isEnabled() ? "rules+langchain4j" : "rules");
        out.put("tasks", tasks.count());
        return out;
    }
}
