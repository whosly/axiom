package com.axiom.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 图表类型（DDD 值对象-枚举）/ Chart type (DDD value-object enum).
 */
public enum ChartType {
    LINE("line", "折线图"),
    BAR("bar", "柱状图"),
    PIE("pie", "饼图"),
    SCATTER("scatter", "散点图"),
    TABLE("table", "表格");

    private final String value;
    private final String label;

    ChartType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    @JsonValue
    public String getValue() { return value; }

    public String getLabel() { return label; }

    @JsonCreator
    public static ChartType from(String v) {
        if (v == null || v.isBlank()) return BAR;
        return Arrays.stream(values())
                .filter(c -> c.value.equalsIgnoreCase(v) || c.name().equalsIgnoreCase(v))
                .findFirst().orElse(BAR);
    }
}
