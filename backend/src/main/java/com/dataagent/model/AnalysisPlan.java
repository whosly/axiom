package com.dataagent.model;

import com.dataagent.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 分析计划（DDD 聚合根 / Value Object）.
 *
 * <p>由 LLM 从自然语言生成、由确定性引擎执行的分析计划。所有领域概念（意图、聚合、粒度、
 * 图表类型、排序）都用 DDD 枚举表达，而非裸字符串，保证类型安全与行为内聚。
 * / Analysis plan produced by the LLM from natural language and executed by the deterministic
 * engine. All domain concepts (intent, aggregation, granularity, chart, sort) are expressed as
 * DDD enums rather than raw strings, ensuring type safety and cohesive behavior.
 *
 * <p>JSON 兼容：枚举通过 @JsonValue 输出小写字符串，LLM 输出与旧数据无需修改。
 * / JSON compatible: enums serialize to lowercase strings via @JsonValue, so LLM output and
 * legacy data need no changes.
 */
@Data
public class AnalysisPlan {
    private AnalysisIntent intent = AnalysisIntent.QUERY;
    private List<String> metrics;
    private Aggregation aggregation = Aggregation.SUM;
    private String dimension;
    private String timeColumn;
    private TimeGranularity timeGranularity = TimeGranularity.MONTH;
    private Map<String, Object> filters;
    private String sortBy;
    private SortOrder sortOrder = SortOrder.DESC;
    @JsonProperty("limit")
    private int limit = 20;
    private ChartType chartType = ChartType.BAR;
    private String title;
    private String reasoning;

    // ---- DDD 便利方法 / Convenience methods ----

    /** 意图是否为趋势分析 / Whether the intent is trend. */
    public boolean isTrend() { return intent == AnalysisIntent.TREND; }

    /** 是否有明确指标 / Whether an explicit metric is specified. */
    public boolean hasMetrics() { return metrics != null && !metrics.isEmpty(); }

    /** 是否有分组维度 / Whether a group-by dimension is specified. */
    public boolean hasDimension() { return dimension != null && !dimension.isBlank(); }
}
