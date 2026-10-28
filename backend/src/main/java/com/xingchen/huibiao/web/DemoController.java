package com.xingchen.huibiao.web;

import com.xingchen.huibiao.dto.ApiRequests.DemoRequest;
import com.xingchen.huibiao.service.DemoService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoService demo;

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestBody(required = false) DemoRequest request) {
        String preset = request == null || request.preset() == null ? "leave" : request.preset();
        return demo.prepareMap(preset);
    }
}
