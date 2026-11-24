package com.axiom.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 时间粒度（DDD 值对象-枚举）/ Time granularity (DDD value-object enum).
 *
 * <p>关键职责：不仅决定 DATE_TRUNC 的截断单位，还决定 period 的**展示格式**。
 * 这修复了一个真实缺陷：按月聚合时 DATE_TRUNC 返回 2026-01-01（当月第一天），
 * 用户看到的是年月日，与"每月总额"的语义不符。本枚举用 strftime 将其格式化为 2026-01。
 * Key responsibility: decides not only the DATE_TRUNC unit but also the **display format**
 * of period. This fixes a real defect: month aggregation returned 2026-01-01 (first day
 * of month), which contradicts "monthly total" semantics. We format it to 2026-01 via strftime.
 */
public enum TimeGranularity {
    HOUR("hour", "hour", "%Y-%m-%d %H:00", "小时"),
    DAY("day", "day", "%Y-%m-%d", "日"),
    // %G 是 ISO 年份：跨年周（如 2024-12-31 属 ISO 2025-W01）必须配 %G 而非 %Y，否则
    // 同一 ISO 周会被拆到两个年份、排序错乱。/ %G is the ISO year; ISO weeks spanning a
    // year boundary must use %G, or the same week splits across two years and mis-sorts.
    WEEK("week", "week", "%G-W%V", "周"),
    MONTH("month", "month", "%Y-%m", "月"),
    QUARTER("quarter", "quarter", "%Y-Q%q", "季度"),
    YEAR("year", "year", "%Y", "年");

    private final String value;
    private final String truncUnit;
    /** DuckDB strftime 格式，决定 period 展示 / DuckDB strftime pattern for period display. */
    private final String format;
    private final String label;

    TimeGranularity(String value, String truncUnit, String format, String label) {
        this.value = value;
        this.truncUnit = truncUnit;
        this.format = format;
        this.label = label;
    }

    @JsonValue
    public String getValue() { return value; }

    public String getTruncUnit() { return truncUnit; }

    public String getFormat() { return format; }

    public String getLabel() { return label; }

    /**
     * 生成 period 的 SQL 表达式 / Build the SQL expression for the period column.
     *
     * <p>用 strftime 包裹 DATE_TRUNC，直接输出格式化后的字符串（如 2026-01），
     * 避免 Java 层再做时间解析。/ Wrap DATE_TRUNC with strftime to output a formatted
     * string directly (e.g. 2026-01), avoiding time parsing in Java.
     *
     * @param timeColumnExpr 已被引号包裹的时间列表达式 / Quoted time column expression.
     * @return period 的 SQL 表达式 / SQL expression for period.
     */
    public String periodSql(String timeColumnExpr) {
        return "strftime(DATE_TRUNC('" + truncUnit + "', CAST(" + timeColumnExpr + " AS TIMESTAMP)), '"
                + format + "')";
    }

    @JsonCreator
    public static TimeGranularity from(String v) {
        if (v == null || v.isBlank()) return MONTH;
        return Arrays.stream(values())
                .filter(g -> g.value.equalsIgnoreCase(v) || g.name().equalsIgnoreCase(v))
                .findFirst().orElse(MONTH);
    }
}
