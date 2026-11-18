package com.dataagent.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** 排序方向（DDD 值对象-枚举）/ Sort direction (DDD value-object enum). */
public enum SortOrder {
    ASC("asc", "升序"), DESC("desc", "降序");

    private final String value;
    private final String label;
    SortOrder(String value, String label) { this.value = value; this.label = label; }

    @JsonValue public String getValue() { return value; }
    public String getLabel() { return label; }
    public String getSql() { return this == ASC ? "ASC" : "DESC"; }

    @JsonCreator
    public static SortOrder from(String v) {
        if (v == null || v.isBlank()) return DESC;
        return Arrays.stream(values())
                .filter(s -> s.value.equalsIgnoreCase(v) || s.name().equalsIgnoreCase(v))
                .findFirst().orElse(DESC);
    }
}
