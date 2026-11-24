package com.axiom.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 分析结果 / Analysis result.
 *
 * <p>只承载下游真正消费的字段：rows/sql 由引擎产出，insights 为规则洞察，
 * chart 供前端渲染，conclusion 为最终结论文本。/ Only fields actually consumed
 * downstream: engine-produced rows/sql, rule insights, chart spec and conclusion.
 */
@Data
public class AnalysisResult {
    private String conclusion;
    private List<String> insights;
    private ChartSpec chart;
    private List<Map<String, Object>> rows;
    private String sql;
}
