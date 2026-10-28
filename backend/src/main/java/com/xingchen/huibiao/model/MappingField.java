package com.xingchen.huibiao.model;

import java.util.List;

public record MappingField(String field, String label, List<String> aliases, int count) {
}
