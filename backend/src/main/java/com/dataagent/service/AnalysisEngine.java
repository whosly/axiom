package com.dataagent.service;

import com.dataagent.domain.*;
import com.dataagent.model.*;
import com.dataagent.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 分析引擎（DDD 领域服务）/ Analysis engine (DDD domain service).
 *
 * <p>把 AnalysisPlan（值对象）确定性地转换为 DuckDB SQL 并执行，产出图表就绪的结果。
 * 所有领域概念用 DDD 枚举表达，通过枚举的方法生成 SQL（如 Aggregation.getSql、
 * TimeGranularity.periodSql），消除散落的字符串判断。
 * / Deterministically turns an AnalysisPlan value object into DuckDB SQL and produces
 * chart-ready results. All domain concepts are DDD enums whose methods generate SQL
 * (Aggregation.getSql, TimeGranularity.periodSql), removing scattered string checks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisEngine {

    private final DatasetRepository datasetRepo;

    /**
     * 执行分析计划 / Execute an analysis plan.
     *
     * @param dataset 数据集聚合 / Dataset aggregate.
     * @param plan    分析计划值对象 / Analysis plan value object.
     * @return 分析结果 / Analysis result.
     */
    public AnalysisResult execute(Dataset dataset, AnalysisPlan plan) {
        String table = dataset.getTableName();
        AnalysisIntent intent = plan.getIntent() == null ? AnalysisIntent.QUERY : plan.getIntent();
        try {
            return switch (intent) {
                case TREND -> trend(dataset, plan, table);
                case ANOMALY -> anomaly(dataset, plan, table);
                case COMPARE -> compare(dataset, plan, table);
                case SUMMARY -> summary(dataset, plan, table);
                case QUERY -> query(dataset, plan, table);
            };
        } catch (Exception e) {
            log.error("[Analysis] failed: {}", e.getMessage(), e);
            AnalysisResult error = new AnalysisResult();
            error.setType(intent.getValue());
            error.setTitle(plan.getTitle() != null ? plan.getTitle() : "分析失败");
            error.setConclusion("分析执行失败：" + e.getMessage());
            error.setInsights(List.of("请检查问题中的字段名是否存在于数据集"));
            error.setRows(List.of());
            return error;
        }
    }

    // ---- 数据查询 / query ----

    private AnalysisResult query(Dataset dataset, AnalysisPlan plan, String table) {
        String agg = plan.getAggregation().getSql();
        List<String> metrics = plan.hasMetrics()
                ? plan.getMetrics()
                : List.of(firstNumericOrOne(dataset));

        List<String> selectParts = new ArrayList<>();
        if (plan.hasDimension()) selectParts.add(q(plan.getDimension()) + " AS dimension");
        for (String m : metrics) {
            selectParts.add(agg + "(" + q(m) + ") AS \"" + m + "_" + agg.toLowerCase() + "\"");
        }

        StringBuilder sql = new StringBuilder("SELECT " + String.join(", ", selectParts) + " FROM " + q(table));
        sql.append(whereClause(plan));
        if (plan.hasDimension()) sql.append(" GROUP BY ").append(q(plan.getDimension()));
        sql.append(orderClause(plan));

        List<Map<String, Object>> rows = datasetRepo.query(sql.toString(), plan.getLimit());
        AnalysisResult result = base(plan, rows, sql.toString(), "数据查询");

        String xField = plan.hasDimension() ? "dimension" : metrics.get(0);
        String yField = !rows.isEmpty() ? rows.get(0).keySet().stream()
                .filter(k -> !k.equals("dimension")).findFirst().orElse(metrics.get(0)) : metrics.get(0);
        ChartType chart = plan.getChartType() == ChartType.TABLE ? ChartType.BAR : plan.getChartType();
        result.setChart(new ChartSpec(chart.getValue(), xField, List.of(yField), plan.getTitle(), rows));
        return result;
    }

    // ---- 趋势分析 / trend ----

    private AnalysisResult trend(Dataset dataset, AnalysisPlan plan, String table) {
        String timeCol = plan.getTimeColumn() != null ? plan.getTimeColumn() : firstTemporal(dataset);
        if (timeCol == null) throw new IllegalArgumentException("数据集中没有时间列，无法做趋势分析");

        TimeGranularity granularity = plan.getTimeGranularity();
        String agg = plan.getAggregation().getSql();
        String metric = plan.hasMetrics() ? plan.getMetrics().get(0) : firstNumeric(dataset);
        if (metric == null) throw new IllegalArgumentException("数据集中没有数值列，无法做趋势分析");

        // 关键修复：用 granularity.periodSql 生成格式化后的 period（如 2026-01），
        // 而非裸 DATE_TRUNC（返回 2026-01-01，与"每月总额"语义不符）。
        // Key fix: use granularity.periodSql to produce a formatted period (e.g. 2026-01)
        // instead of raw DATE_TRUNC (which returns 2026-01-01, contradicting "monthly total").
        String periodExpr = granularity.periodSql(q(timeCol));
        String sql = """
            SELECT %s AS period, %s(%s) AS value
            FROM %s
            WHERE %s IS NOT NULL
            GROUP BY period ORDER BY period
            """.formatted(periodExpr, agg, q(metric), q(table), q(timeCol));

        List<Map<String, Object>> rows = datasetRepo.query(sql, plan.getLimit());
        AnalysisResult result = base(plan, rows, sql, "趋势分析");
        result.setChart(new ChartSpec(ChartType.LINE.getValue(), "period", List.of("value"),
                plan.getTitle() != null ? plan.getTitle() : metric + " " + granularity.getLabel() + "趋势", rows));

        List<String> insights = new ArrayList<>();
        if (rows.size() >= 2) {
            Object first = rows.get(0).get("value"), last = rows.get(rows.size() - 1).get("value");
            if (first instanceof Number a && last instanceof Number b && a.doubleValue() != 0) {
                double change = (b.doubleValue() - a.doubleValue()) / Math.abs(a.doubleValue()) * 100;
                insights.add(String.format("%s内 %s 从 %.2f 变化至 %.2f，累计变化 %.1f%%",
                        granularity.getLabel(), metric, a.doubleValue(), b.doubleValue(), change));
            }
        }
        result.setInsights(insights);
        return result;
    }

    // ---- 异常识别 / anomaly ----

    private AnalysisResult anomaly(Dataset dataset, AnalysisPlan plan, String table) {
        String metric = plan.hasMetrics() ? plan.getMetrics().get(0) : firstNumeric(dataset);
        if (metric == null) throw new IllegalArgumentException("数据集中没有数值列，无法做异常识别");

        String groupCol = plan.hasDimension() ? plan.getDimension() : null;
        String sql = groupCol == null
                ? "SELECT CAST(%s AS VARCHAR) AS item, %s AS value FROM %s WHERE %s IS NOT NULL"
                   .formatted(q(metric), q(metric), q(table), q(metric))
                : "SELECT CAST(%s AS VARCHAR) AS item, %s(%s) AS value FROM %s WHERE %s IS NOT NULL GROUP BY %s"
                   .formatted(q(groupCol), plan.getAggregation().getSql(), q(metric), q(table), q(metric), q(groupCol));

        List<Map<String, Object>> raw = datasetRepo.query(sql, 10000);
        List<Map<String, Object>> anomalies = detectAnomalies(raw);
        String resultSql = sql + " -- z-score / IQR anomaly detection";
        AnalysisResult result = base(plan, anomalies, resultSql, "异常识别");
        result.setChart(new ChartSpec(ChartType.SCATTER.getValue(), "item", List.of("value"),
                plan.getTitle() != null ? plan.getTitle() : metric + " 异常检测", anomalies));

        List<String> insights = new ArrayList<>();
        insights.add(String.format("共检测 %d 个数据点，发现 %d 个异常点", raw.size(), anomalies.size()));
        for (int i = 0; i < Math.min(3, anomalies.size()); i++) {
            Map<String, Object> a = anomalies.get(i);
            insights.add(String.format("异常点 %s: 值 %.2f，偏离度 %.2f", a.get("item"),
                    ((Number) a.get("value")).doubleValue(), ((Number) a.get("deviation")).doubleValue()));
        }
        result.setInsights(insights);
        return result;
    }

    /** Z-score + IQR 双准则异常检测 / Anomaly detection via z-score and IQR. */
    private List<Map<String, Object>> detectAnomalies(List<Map<String, Object>> rows) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get("value");
            if (v instanceof Number n) values.add(n.doubleValue());
        }
        if (values.size() < 3) return List.of();

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = Math.sqrt(values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double q1 = sorted.get((int) (sorted.size() * 0.25));
        double q3 = sorted.get((int) (sorted.size() * 0.75));
        double iqr = q3 - q1;
        double lowFence = q1 - 1.5 * iqr, highFence = q3 + 1.5 * iqr;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object vObj = r.get("value");
            if (!(vObj instanceof Number)) continue;
            double v = ((Number) vObj).doubleValue();
            double z = sd > 0 ? (v - mean) / sd : 0;
            if (Math.abs(z) > 2.0 || v < lowFence || v > highFence) {
                Map<String, Object> a = new LinkedHashMap<>(r);
                a.put("deviation", Math.round(Math.abs(z) * 100) / 100.0);
                a.put("direction", v > mean ? "高" : "低");
                out.add(a);
            }
        }
        out.sort(Comparator.comparingDouble(m -> -Math.abs(((Number) m.get("deviation")).doubleValue())));
        return out;
    }

    // ---- 指标对比 / compare ----

    private AnalysisResult compare(Dataset dataset, AnalysisPlan plan, String table) {
        String dimension = plan.hasDimension() ? plan.getDimension() : firstCategorical(dataset);
        if (dimension == null) throw new IllegalArgumentException("数据集中没有类别列，无法做指标对比");

        String agg = plan.getAggregation().getSql();
        List<String> metrics = plan.hasMetrics() ? plan.getMetrics() : List.of(firstNumericOrOne(dataset));

        List<String> selects = new ArrayList<>();
        selects.add(q(dimension) + " AS dimension");
        for (String m : metrics) selects.add(agg + "(" + q(m) + ") AS \"" + m + "\"");
        String sql = "SELECT " + String.join(", ", selects) + " FROM " + q(table)
                + whereClause(plan) + " GROUP BY " + q(dimension) + orderClause(plan);

        List<Map<String, Object>> rows = datasetRepo.query(sql, plan.getLimit());
        AnalysisResult result = base(plan, rows, sql, "指标对比");
        result.setChart(new ChartSpec(plan.getChartType().getValue(), "dimension", metrics,
                plan.getTitle() != null ? plan.getTitle() : "指标对比", rows));

        List<String> insights = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            for (String m : metrics) {
                Object v = r.get(m);
                if (v instanceof Number n) {
                    double total = rows.stream().mapToDouble(x -> x.get(m) instanceof Number z ? z.doubleValue() : 0).sum();
                    if (total > 0) insights.add(String.format("%s 的 %s 占比 %.1f%%",
                            r.get("dimension"), m, n.doubleValue() / total * 100));
                }
            }
        }
        result.setInsights(insights);
        return result;
    }

    // ---- 数据概览 / summary ----

    private AnalysisResult summary(Dataset dataset, AnalysisPlan plan, String table) {
        List<String> numeric = allNumeric(dataset);
        Map<String, Object> stats = new LinkedHashMap<>();
        for (String col : numeric.subList(0, Math.min(5, numeric.size()))) {
            stats.put(col, datasetRepo.stats(table, col));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(stats);

        AnalysisResult result = base(plan, rows, "-- descriptive statistics", "数据概览");
        List<Map<String, Object>> chartRows = new ArrayList<>();
        for (String c : numeric) {
            Object stat = stats.get(c);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", c);
            row.put("avg", stat instanceof Map<?, ?> sm ? sm.get("avg") : 0);
            chartRows.add(row);
        }
        result.setChart(new ChartSpec(ChartType.BAR.getValue(), "metric", List.of("avg"), "数值指标均值", chartRows));

        List<String> insights = new ArrayList<>();
        insights.add(String.format("数据集共 %d 行、%d 列", dataset.getRowCount(), dataset.getColumnCount()));
        for (String col : numeric.subList(0, Math.min(3, numeric.size()))) {
            Map<?, ?> s = (Map<?, ?>) stats.get(col);
            insights.add(String.format("%s：均值 %.2f，最小 %.2f，最大 %.2f",
                    col, num(s.get("avg")), num(s.get("min")), num(s.get("max"))));
        }
        result.setInsights(insights);
        return result;
    }

    // ---- helpers ----

    private AnalysisResult base(AnalysisPlan plan, List<Map<String, Object>> rows, String sql, String fallbackTitle) {
        AnalysisResult r = new AnalysisResult();
        r.setType(plan.getIntent().getValue());
        r.setTitle(plan.getTitle() != null ? plan.getTitle() : fallbackTitle);
        r.setRows(rows);
        r.setSql(sql);
        r.setSummary(Map.of("rowCount", rows.size()));
        return r;
    }

    private String whereClause(AnalysisPlan plan) {
        if (plan.getFilters() == null || plan.getFilters().isEmpty()) return "";
        List<String> conditions = new ArrayList<>();
        for (Map.Entry<String, Object> entry : plan.getFilters().entrySet()) {
            String col = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> m) {
                String op = String.valueOf(m.get("op") == null ? "=" : m.get("op"));
                String v = String.valueOf(m.get("value"));
                conditions.add(q(col) + " " + safeOp(op) + " " + sqlLiteral(v));
            } else {
                conditions.add(q(col) + " = " + sqlLiteral(String.valueOf(val)));
            }
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private String orderClause(AnalysisPlan plan) {
        if (plan.getSortBy() == null || plan.getSortBy().isBlank()) return "";
        return " ORDER BY " + q(plan.getSortBy()) + " " + plan.getSortOrder().getSql();
    }

    private String safeOp(String op) {
        return switch (op) {
            case ">", "<", ">=", "<=", "!=", "<>" -> op;
            case "like" -> "LIKE";
            default -> "=";
        };
    }

    private String sqlLiteral(String v) { return "'" + v.replace("'", "''") + "'"; }

    private String q(String ident) { return ident == null ? "" : "\"" + ident.replace("\"", "\"\"") + "\""; }

    private String firstNumeric(Dataset d) {
        return d.getColumns().stream().filter(c -> "numeric".equals(c.getType()))
                .map(ColumnInfo::getName).findFirst().orElse(null);
    }

    private String firstTemporal(Dataset d) {
        return d.getColumns().stream().filter(c -> "temporal".equals(c.getType()))
                .map(ColumnInfo::getName).findFirst().orElse(null);
    }

    private String firstCategorical(Dataset d) {
        return d.getColumns().stream().filter(c -> "categorical".equals(c.getType()))
                .map(ColumnInfo::getName).findFirst().orElse(null);
    }

    private String firstNumericOrOne(Dataset d) {
        String c = firstNumeric(d);
        return c == null ? "1" : c;
    }

    private List<String> allNumeric(Dataset d) {
        List<String> list = d.getColumns().stream().filter(c -> "numeric".equals(c.getType()))
                .map(ColumnInfo::getName).toList();
        return list.isEmpty() ? List.of("1") : list;
    }

    private double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
}
