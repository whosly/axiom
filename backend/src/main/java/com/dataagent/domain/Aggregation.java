package com.dataagent.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 聚合方式（DDD 值对象-枚举）/ Aggregation function (DDD value-object enum).
 *
 * <p>封装聚合的 SQL 关键字与展示名，避免散落的 safeAgg() 字符串判断。
 * / Encapsulates the SQL keyword and display name, replacing scattered safeAgg() string checks.
 */
public enum Aggregation {
    SUM("sum", "SUM", "求和"),
    AVG("avg", "AVG", "平均"),
    COUNT("count", "COUNT", "计数"),
    MIN("min", "MIN", "最小"),
    MAX("max", "MAX", "最大");

    private final String value;
    private final String sql;
    private final String label;

    Aggregation(String value, String sql, String label) {
        this.value = value;
        this.sql = sql;
        this.label = label;
    }

    @JsonValue
    public String getValue() { return value; }

    /** 对应的 SQL 聚合函数 / Corresponding SQL aggregate function. */
    public String getSql() { return sql; }

    public String getLabel() { return label; }

    @JsonCreator
    public static Aggregation from(String v) {
        if (v == null || v.isBlank()) return SUM;
        return Arrays.stream(values())
                .filter(a -> a.value.equalsIgnoreCase(v) || a.sql.equalsIgnoreCase(v)
                        || a.name().equalsIgnoreCase(v) || "average".equalsIgnoreCase(v) && a == AVG)
                .findFirst().orElse(SUM);
    }
}
