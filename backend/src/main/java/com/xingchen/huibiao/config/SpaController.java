package com.xingchen.huibiao.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Vue 用的是 history 路由，刷新 /tasks/T2026... 时后端要退回 index.html。
 * 只拦页面路径，/api 和 /docs 不受影响。
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/tasks", "/tasks/**", "/sheets", "/sheets/**", "/ops"})
    public String index() {
        return "forward:/index.html";
    }

    @GetMapping({"/docs", "/docs/"})
    public String docs() {
        return "forward:/docs/index.html";
    }
}
