package com.xingchen.huibiao.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xingchen.huibiao.service.FileService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService files;
    private final ObjectMapper mapper;

    public FileController(FileService files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    /**
     * 上传文件。返回体就是文件元数据，额外带一个 duplicate 标记：
     * 内容与已登记文件一致时后端会复用旧记录，前端据此提示用户“没有重复添加”。
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "role", defaultValue = "auto") String role) {
        FileService.UploadResult result = files.upload(file, role);
        Map<String, Object> body = mapper.convertValue(result.file(), new TypeReference<LinkedHashMap<String, Object>>() {
        });
        body.put("duplicate", result.duplicate());
        return body;
    }
}
