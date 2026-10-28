package com.xingchen.huibiao.web;

import com.xingchen.huibiao.dto.ApiRequests.MappingSchemeRequest;
import com.xingchen.huibiao.model.MappingScheme;
import com.xingchen.huibiao.service.MappingSchemeStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 映射方案的保存 / 加载 / 删除。方案存服务端，换浏览器也能复用。
 */
@RestController
@RequestMapping("/api/mapping-schemes")
public class MappingSchemeController {

    private final MappingSchemeStore schemes;

    public MappingSchemeController(MappingSchemeStore schemes) {
        this.schemes = schemes;
    }

    @GetMapping
    public List<MappingScheme> list() {
        return schemes.all();
    }

    @PostMapping
    public MappingScheme save(@RequestBody MappingSchemeRequest request) {
        return schemes.save(request);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        if (!schemes.remove(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有找到这个映射方案");
        }
        return Map.of("deleted", true);
    }
}
