package com.axiom.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 分析意图（DDD 值对象-枚举）/ Analysis intent (DDD value-object enum).
 *
 * <p>表达用户问题的分析类型。用枚举替代裸字符串，保证类型安全、穷举 switch、防止拼写错误。
 * / Expresses the analysis type of a user question. Using an enum instead of raw strings
 * gives type safety, exhaustive switch and prevents typos.
 *
 * <p>JSON 序列化使用小写 value，保持与 LLM 输出和前端兼容。
 * / JSON serialization uses lowercase values for compatibility with LLM output and frontend.
 */
public enum AnalysisIntent {
    QUERY("query", "数据查询"),
    TREND("trend", "趋势分析"),
    ANOMALY("anomaly", "异常识别"),
    COMPARE("compare", "指标对比"),
    SUMMARY("summary", "数据概览");

    private final String value;
    private final String label;

    AnalysisIntent(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /** JSON 输出值 / JSON output value. */
    @JsonValue
    public String getValue() { return value; }

    /** 中文标签 / Chinese label. */
    public String getLabel() { return label; }

    /** 从字符串解析（兼容大小写与非法值回退 QUERY）/ Parse from string with fallback. */
    @JsonCreator
    public static AnalysisIntent from(String v) {
        if (v == null || v.isBlank()) return QUERY;
        return Arrays.stream(values())
                .filter(i -> i.value.equalsIgnoreCase(v) || i.name().equalsIgnoreCase(v))
                .findFirst().orElse(QUERY);
    }
}
