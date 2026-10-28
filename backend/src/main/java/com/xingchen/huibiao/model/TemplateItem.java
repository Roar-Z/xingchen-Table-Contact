package com.xingchen.huibiao.model;

import java.util.List;

public record TemplateItem(String id, String name, String scene, boolean builtIn,
                           String description, List<String> columns) {
}
