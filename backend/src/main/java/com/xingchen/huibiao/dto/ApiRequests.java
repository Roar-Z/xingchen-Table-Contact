package com.xingchen.huibiao.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class ApiRequests {

    private ApiRequests() {
    }

    public record FileRef(String fileId, String role) {
    }

    /**
     * templateFileId 为空时用内置 HR 模板；传了就按上传的模板决定导出列。
     */
    public record AnalyzeRequest(String brief, List<FileRef> files, String templateFileId) {
    }

    /**
     * column：直接取源表某一列。
     * expr：源表列不够用时，用表达式拼出来，例如 {起始日期} {起始时间}、"是"、{天}*8。
     *       两者只填一个，填了 expr 就以它为准。
     */
    public record MappingEntry(String target, String fileId, String column, String expr) {
    }

    /**
     * isolateAnomaly：脏数据隔离，默认开；关了就不写异常日志。
     * strictFormat：严格格式校验，默认关；开了以后模板列对不上就直接拦下。
     */
    public record TaskConfig(String scene, List<MappingEntry> mapping, String templateFileId,
                             Boolean isolateAnomaly, Boolean strictFormat) {
    }

    public record PreviewRequest(String brief, List<FileRef> files, TaskConfig config) {
    }

    public record CreateTaskRequest(String name, String brief, List<FileRef> files, TaskConfig config) {
    }

    public record DeleteTasksRequest(List<String> ids) {
    }

    /**
     * 映射方案只记文件名和列名，不记内部 fileId，换一台机器上传同名文件也能加载。
     */
    public record MappingSchemeEntry(String target, @JsonProperty("file_name") String fileName,
                                     String column, String expr) {
    }

    public record MappingSchemeRequest(String name, String scene,
                                       @JsonProperty("template_name") String templateName,
                                       List<MappingSchemeEntry> mapping) {
    }

    public record DemoRequest(String preset) {
    }

    /**
     * 结果表的自然语言修改：instruction 是用户原话，例如“第 3 行开始时间改成 09:00”。
     */
    public record ReviseRequest(String instruction) {
    }
}
