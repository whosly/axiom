package com.axiom.service;

import com.axiom.domain.*;
import com.axiom.model.AnalysisPlan;
import com.axiom.model.ColumnInfo;
import com.axiom.model.Dataset;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 规则降级规划器 / Rule-based fallback planner.
 *
 * <p>当 LLM 不可用或缺 Key 时，从中文/英文关键词推断意图并映射字段。所有领域概念用 DDD 枚举。
 * / When the LLM is unavailable, infers intent from keywords and maps columns. All domain
 * concepts use DDD enums.
 */
@Service
public class RuleBasedPlanner {

    /**
     * 生成分析计划 / Generate an analysis plan.
     *
     * @param dataset  数据集 / Dataset.
     * @param question 用户问题 / User question.
     * @return 分析计划 / Analysis plan.
     */
    public AnalysisPlan plan(Dataset dataset, String question) {
        String q = question.toLowerCase();
        AnalysisPlan plan = new AnalysisPlan();
        plan.setLimit(20);
        plan.setChartType(ChartType.BAR);
        plan.setReasoning("规则引擎生成（未配置大模型时自动降级）/ Rule-based fallback");

        String numeric = pickColumn(dataset, c -> "numeric".equals(c.getType()), q, null);
        String temporal = pickColumn(dataset, c -> "temporal".equals(c.getType()), q, null);
        String categorical = pickColumn(dataset, c -> "categorical".equals(c.getType()), q, null);

        if (matches(q, "异常", "离群", "突变", "波动", "anomal", "outlier")) {
            plan.setIntent(AnalysisIntent.ANOMALY);
            plan.setMetrics(numeric != null ? List.of(numeric) : List.of());
            plan.setAggregation(Aggregation.AVG);
            plan.setChartType(ChartType.SCATTER);
            plan.setTitle("异常值识别 · " + (numeric != null ? numeric : ""));
            return plan;
        }
        if (matches(q, "趋势", "走势", "变化", "增长", "逐月", "逐日", "随时间", "trend", "over time")) {
            plan.setIntent(AnalysisIntent.TREND);
            plan.setMetrics(numeric != null ? List.of(numeric) : List.of());
            plan.setAggregation(Aggregation.SUM);
            plan.setTimeColumn(temporal);
            plan.setTimeGranularity(inferGranularity(q));
            plan.setChartType(ChartType.LINE);
            plan.setTitle((numeric != null ? numeric : "指标") + " 趋势分析");
            return plan;
        }
        if (matches(q, "对比", "比较", "各", "排名", "排行", "占比", "分布", "按", "compare", "by ", "versus")) {
            plan.setIntent(AnalysisIntent.COMPARE);
            plan.setMetrics(numeric != null ? List.of(numeric) : List.of());
            plan.setAggregation(matches(q, "订单", "数量", "次数", "count") ? Aggregation.COUNT : Aggregation.SUM);
            plan.setDimension(categorical);
            plan.setSortBy(numeric);
            plan.setSortOrder(SortOrder.DESC);
            plan.setChartType(matches(q, "占比", "分布") ? ChartType.PIE : ChartType.BAR);
            plan.setTitle((numeric != null ? numeric : "指标") + (categorical != null ? "按" + categorical : "") + "对比");
            return plan;
        }
        if (matches(q, "概览", "概况", "描述", "统计", "总结", "整体", "summary", "overview")) {
            plan.setIntent(AnalysisIntent.SUMMARY);
            plan.setTitle("数据概览");
            plan.setChartType(ChartType.BAR);
            return plan;
        }

        plan.setIntent(AnalysisIntent.QUERY);
        plan.setMetrics(numeric != null ? List.of(numeric) : List.of());
        plan.setAggregation(matches(q, "平均", "均值", "avg", "average") ? Aggregation.AVG
                : matches(q, "订单", "数量", "次数", "count") ? Aggregation.COUNT : Aggregation.SUM);
        plan.setDimension(categorical);
        plan.setSortBy(numeric);
        plan.setSortOrder(SortOrder.DESC);
        plan.setTitle("数据查询结果");
        return plan;
    }

    /** 生成结论 / Generate a readable conclusion. */
    public String conclude(Dataset dataset, String question, AnalysisPlan plan, com.axiom.model.AnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("针对「").append(question).append("」的分析结果如下：\n\n");
        if (result.getInsights() != null && !result.getInsights().isEmpty()) {
            sb.append("关键发现：\n");
            for (String insight : result.getInsights()) sb.append("- ").append(insight).append("\n");
        } else if (result.getRows() != null && !result.getRows().isEmpty()) {
            sb.append("分析结果共 ").append(result.getRows().size()).append(" 条记录：\n");
            for (int i = 0; i < Math.min(5, result.getRows().size()); i++) {
                sb.append("- ").append(formatRow(result.getRows().get(i))).append("\n");
            }
        }
        sb.append("\n说明：当前分析由内置规则引擎生成（本地大模型未配置）。");
        return sb.toString();
    }

    private String formatRow(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (sb.length() > 0) sb.append("，");
            Object v = e.getValue();
            // %f 只接受浮点类型；COUNT 返回 Long、SUM 可能返回 Integer，
            // 必须转 doubleValue() 否则抛 IllegalFormatConversionException。
            // %f accepts only floating point; COUNT returns Long and SUM may return Integer,
            // so convert via doubleValue() to avoid IllegalFormatConversionException.
            sb.append(e.getKey()).append("=")
              .append(v instanceof Number n ? String.format("%.2f", n.doubleValue()) : v);
        }
        return sb.toString();
    }

    private boolean matches(String q, String... keywords) {
        for (String k : keywords) if (q.contains(k)) return true;
        return false;
    }

    private TimeGranularity inferGranularity(String q) {
        if (matches(q, "日", "每天", "逐日", "day")) return TimeGranularity.DAY;
        if (matches(q, "周", "每周", "week")) return TimeGranularity.WEEK;
        if (matches(q, "季", "quarter")) return TimeGranularity.QUARTER;
        if (matches(q, "年", "每年", "year")) return TimeGranularity.YEAR;
        return TimeGranularity.MONTH;
    }

    private String pickColumn(Dataset dataset, java.util.function.Predicate<ColumnInfo> typeFilter,
                              String question, String fallback) {
        List<ColumnInfo> candidates = dataset.getColumns().stream().filter(typeFilter).toList();
        if (candidates.isEmpty()) return fallback;
        for (ColumnInfo c : candidates) if (question.contains(c.getName().toLowerCase())) return c.getName();
        Map<String, String[]> hints = Map.of(
                "销售", new String[]{"销售额", "sales", "revenue", "金额", "收入"},
                "订单", new String[]{"订单数", "订单", "数量", "count", "order"},
                "日期", new String[]{"日期", "时间", "date"},
                "利润", new String[]{"利润", "profit"});
        for (Map.Entry<String, String[]> h : hints.entrySet()) {
            if (question.contains(h.getKey())) {
                for (String hint : h.getValue())
                    for (ColumnInfo c : candidates)
                        if (c.getName().toLowerCase().contains(hint)) return c.getName();
            }
        }
        return candidates.get(0).getName();
    }
}
