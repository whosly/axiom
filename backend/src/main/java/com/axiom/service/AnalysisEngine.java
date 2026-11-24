package com.axiom.service;

import com.axiom.domain.*;
import com.axiom.model.*;
import com.axiom.repository.DatasetRepository;
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
        // 聚合查询的 ORDER BY 必须引用别名（如 "订单数_count"），不能引用原始列，
        // 否则 DuckDB 报 "column must appear in GROUP BY"。
        // Aggregated queries must ORDER BY the alias, not the raw column, or DuckDB rejects it.
        sql.append(aggOrderClause(plan, agg, metrics));

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
        List<String> metrics = plan.hasMetrics() ? plan.getMetrics() : List.of(firstNumeric(dataset));
        if (metrics.isEmpty() || metrics.get(0) == null) throw new IllegalArgumentException("数据集中没有数值列，无法做趋势分析");

        // 关键修复 1：用 granularity.periodSql 生成格式化后的 period（如 2026-01）。
        // 关键修复 2：filters 必须生效——"华东每月趋势"不能返回全国数据。
        // 关键修复 3：支持 dimension 多序列与多指标，"各产品每月销售额"不再退化为整体趋势。
        // Key fixes: formatted period; filters applied; dimension/multi-metric supported.
        String periodExpr = granularity.periodSql(q(timeCol));
        boolean hasDim = plan.hasDimension();
        // whereClause 返回 " WHERE ..." 前缀，此处已有 WHERE，必须转换为 " AND ..." 拼接，
        // 否则会产生双 WHERE 非法 SQL。/ whereClause() returns a " WHERE ..." prefix; the
        // statement already has a WHERE, so filters must be joined with AND to avoid
        // producing invalid double-WHERE SQL.
        String baseWhere = q(timeCol) + " IS NOT NULL" + andFilters(plan);

        // 先取最近的 N 个 period（降序），再对全量行按这些 period 聚合。
        // 升序 + LIMIT 截掉的是最早的数据，趋势洞察会基于过期时间片。
        // Take the latest N periods first; ascending + LIMIT would keep the oldest ones.
        String recentPeriods = "SELECT " + periodExpr + " AS period FROM " + q(table)
                + " WHERE " + baseWhere + " GROUP BY period ORDER BY period DESC LIMIT " + Math.max(1, plan.getLimit());

        List<String> selects = new ArrayList<>();
        selects.add(periodExpr + " AS period");
        if (hasDim) selects.add(q(plan.getDimension()) + " AS dimension");
        for (String m : metrics) selects.add(agg + "(" + q(m) + ") AS \"" + m + "\"");

        String sql = "SELECT " + String.join(", ", selects) + " FROM " + q(table)
                + " WHERE " + baseWhere + " AND " + periodExpr + " IN (" + recentPeriods + ")"
                + " GROUP BY period" + (hasDim ? ", dimension" : "")
                + " ORDER BY period" + (hasDim ? ", dimension" : "");

        List<Map<String, Object>> rows = datasetRepo.query(sql, Integer.MAX_VALUE);
        AnalysisResult result = base(plan, rows, sql, "趋势分析");
        result.setChart(new ChartSpec(ChartType.LINE.getValue(), "period",
                hasDim ? List.of(metrics.get(0)) : metrics,
                plan.getTitle() != null ? plan.getTitle() : metrics.get(0) + " " + granularity.getLabel() + "趋势", rows));

        List<String> insights = trendInsights(rows, metrics, granularity.getLabel(), hasDim);
        result.setInsights(insights);
        return result;
    }

    // ---- 异常识别 / anomaly ----

    private AnalysisResult anomaly(Dataset dataset, AnalysisPlan plan, String table) {
        String metric = plan.hasMetrics() ? plan.getMetrics().get(0) : firstNumeric(dataset);
        if (metric == null) throw new IllegalArgumentException("数据集中没有数值列，无法做异常识别");

        String groupCol = plan.hasDimension() ? plan.getDimension() : null;
        String where = q(metric) + " IS NOT NULL" + andFilters(plan);
        String sql = groupCol == null
                ? "SELECT CAST(%s AS VARCHAR) AS item, %s AS value FROM %s WHERE %s"
                   .formatted(q(metric), q(metric), q(table), where)
                : "SELECT CAST(%s AS VARCHAR) AS item, %s(%s) AS value FROM %s WHERE %s GROUP BY %s"
                   .formatted(q(groupCol), plan.getAggregation().getSql(), q(metric), q(table), where, q(groupCol));

        List<Map<String, Object>> raw = datasetRepo.query(sql, 10000);
        List<Map<String, Object>> anomalies = detectAnomalies(raw);
        String resultSql = sql + " -- z-score / IQR anomaly detection";
        AnalysisResult result = base(plan, anomalies, resultSql, "异常识别");
        result.setChart(new ChartSpec(ChartType.SCATTER.getValue(), "item", List.of("value"),
                plan.getTitle() != null ? plan.getTitle() : metric + " 异常检测", anomalies));

        List<String> insights = new ArrayList<>();
        insights.add(String.format("共检测 %d 个数据点，发现 %d 个异常点", raw.size(), anomalies.size()));
        // 上限截断必须显式提示，否则大表的漏检不可见 / Surface the cap so truncation is visible.
        if (raw.size() >= 10000) {
            insights.add("数据量超过检测上限，仅对前 10000 行执行了统计检验，结果可能不完整");
        }
        if (raw.size() < 6) {
            // 总体标准差下单点最大 z=(n-1)/sqrt(n)，n<=5 时永远 <=2，检测必然为空。
            // 必须说明原因，避免"0 个异常"被误解为数据正常。/ With population std the max
            // achievable z is (n-1)/sqrt(n), so n<=5 can never trigger the threshold.
            insights.add("样本过少（少于 6 个数据点），统计检验不可靠，请提供更多数据后再判断");
        }
        for (int i = 0; i < Math.min(3, anomalies.size()); i++) {
            Map<String, Object> a = anomalies.get(i);
            insights.add(String.format("异常点 %s: 值 %.2f，偏离度 %.2f", a.get("item"),
                    ((Number) a.get("value")).doubleValue(), ((Number) a.get("deviation")).doubleValue()));
        }
        result.setInsights(insights);
        return result;
    }

    /**
     * 趋势洞察：首尾变化 + 峰谷 + 波动幅度 / Trend insights: endpoints, peak/trough and volatility.
     *
     * <p>只看首尾会掩盖中间波动（如 48900→48300 看似平稳，实际峰值 66600、谷值 12450），
     * 因此补充峰谷位置与极差占比，避免结论误导。
     * Endpoint-only comparison hides mid-series swings; include peak/trough and range ratio
     * so the conclusion cannot mislead.
     */
    /**
     * 趋势洞察 / Trend insights.
     *
     * <p>单序列：首尾变化 + 峰谷 + 波动幅度；多序列（带维度）：按维度汇总各序列的
     * 最新值与期间变化，避免把不同维度的点串成一条假趋势。
     * Single series: endpoints, peak/trough and volatility. Multi-series (dimension):
     * per-dimension latest value and change, so points of different dimensions are
     * never chained into one fake trend.
     */
    private List<String> trendInsights(List<Map<String, Object>> rows, List<String> metrics,
                                       String granularityLabel, boolean hasDim) {
        List<String> insights = new ArrayList<>();
        String metric = metrics.get(0);
        if (hasDim) {
            // 多序列：按维度聚合首尾 / Multi-series: per-dimension endpoints.
            Map<String, double[]> series = new LinkedHashMap<>(); // [first, last]
            Map<String, Double> max = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                Object v = r.get(metric);
                if (!(v instanceof Number n)) continue;
                String dim = String.valueOf(r.get("dimension"));
                double d = n.doubleValue();
                double[] fl = series.computeIfAbsent(dim, k -> new double[]{d, d});
                fl[1] = d;
                max.merge(dim, d, Math::max);
            }
            for (Map.Entry<String, double[]> e : series.entrySet()) {
                double a = e.getValue()[0], b = e.getValue()[1];
                String change = a != 0 ? String.format("，变化 %.1f%%", (b - a) / Math.abs(a) * 100) : "";
                insights.add(String.format("%s：%s 期末 %.2f%s", e.getKey(), metric, b, change));
            }
            insights.add(String.format("共 %d 个序列、%d 个 %s，仅展示最近 %d 个 %s",
                    series.size(), rows.size(), granularityLabel, series.size() > 0 ? distinctPeriods(rows) : 0, granularityLabel));
            return insights;
        }

        if (rows.size() < 2) return insights;
        Object first = rows.get(0).get(metric), last = rows.get(rows.size() - 1).get(metric);
        if (!(first instanceof Number) || !(last instanceof Number)) return insights;
        double a = ((Number) first).doubleValue(), b = ((Number) last).doubleValue();
        if (a != 0 && Double.isFinite(a) && Double.isFinite(b)) {
            double change = (b - a) / Math.abs(a) * 100;
            insights.add(String.format("%s内 %s 从 %.2f 变化至 %.2f，累计变化 %.1f%%",
                    granularityLabel, metric, a, b, change));
        }

        // 峰谷与波动幅度（跳过非有限值，防止 Infinity/NaN 污染统计）/ Peak, trough and range
        // (skip non-finite values so Infinity/NaN cannot poison the stats).
        int maxIdx = -1, minIdx = -1;
        double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < rows.size(); i++) {
            Object v = rows.get(i).get(metric);
            if (!(v instanceof Number n)) continue;
            double d = n.doubleValue();
            if (!Double.isFinite(d)) continue;
            if (maxIdx < 0 || d > max) { max = d; maxIdx = i; }
            if (minIdx < 0 || d < min) { min = d; minIdx = i; }
        }
        if (maxIdx >= 0 && minIdx >= 0) {
            String maxPeriod = String.valueOf(rows.get(maxIdx).get("period"));
            String minPeriod = String.valueOf(rows.get(minIdx).get("period"));
            insights.add(String.format("最高 %.2f（%s），最低 %.2f（%s），极差 %.2f",
                    max, maxPeriod, min, minPeriod, max - min));
            double baseline = Math.abs(a) > 0 ? Math.abs(a) : 1;
            insights.add(String.format("期间波动幅度约 %.1f%%（相对首期）",
                    (max - min) / baseline * 100));
        }
        return insights;
    }

    /** 统计去重后的 period 数 / Count distinct periods. */
    private int distinctPeriods(List<Map<String, Object>> rows) {
        Set<String> s = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) s.add(String.valueOf(r.get("period")));
        return s.size();
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
        // IQR=0（超 75% 的值相同）时围栏退化为单点，会把所有不同值误判为异常；此时禁用 IQR 分支。
        // When IQR is zero the fences collapse to one point and flag everything; disable that branch.
        boolean iqrUsable = iqr > 0;
        double lowFence = q1 - 1.5 * iqr, highFence = q3 + 1.5 * iqr;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object vObj = r.get("value");
            if (!(vObj instanceof Number)) continue;
            double v = ((Number) vObj).doubleValue();
            double z = sd > 0 ? (v - mean) / sd : 0;
            if (Math.abs(z) > 2.0 || (iqrUsable && (v < lowFence || v > highFence))) {
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
                + whereClause(plan) + " GROUP BY " + q(dimension) + compareOrderClause(plan, dimension, metrics);

        List<Map<String, Object>> rows = datasetRepo.query(sql, plan.getLimit());
        AnalysisResult result = base(plan, rows, sql, "指标对比");
        result.setChart(new ChartSpec(plan.getChartType().getValue(), "dimension", metrics,
                plan.getTitle() != null ? plan.getTitle() : "指标对比", rows));

        List<String> insights = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            for (String m : metrics) {
                Object v = r.get(m);
                if (v instanceof Number n && Double.isFinite(n.doubleValue())) {
                    double total = rows.stream().mapToDouble(x -> x.get(m) instanceof Number z ? z.doubleValue() : 0).sum();
                    // 占比只在分母为正且当前值非负时才有意义；负值/近零分母会产生 -300% 之类的荒谬数字。
                    // A percentage only makes sense with a positive denominator and a non-negative value.
                    if (total > 0 && n.doubleValue() >= 0) {
                        insights.add(String.format("%s 的 %s 占比 %.1f%%",
                                r.get("dimension"), m, n.doubleValue() / total * 100));
                    }
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
        // 图表列范围必须与 stats 统计范围一致，否则超出部分的列会画出高度为 0 的假柱子。
        // The chart must cover exactly the same columns as stats, or extra columns render as zero bars.
        List<String> charted = numeric.subList(0, Math.min(5, numeric.size()));
        List<Map<String, Object>> chartRows = new ArrayList<>();
        for (String c : charted) {
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

    /** 过滤条件（AND 前缀形式），用于拼接在已有 WHERE 之后 / Filters with AND prefix, for appending after an existing WHERE clause. */
    private String andFilters(AnalysisPlan plan) {
        String wc = whereClause(plan);
        if (wc.isEmpty()) return "";
        // " WHERE a = 'x'" -> " AND a = 'x'"（保留原有空格）/ strip " WHERE" and re-prefix with " AND"
        return " AND " + wc.substring(" WHERE ".length());
    }

    private String orderClause(AnalysisPlan plan) {
        if (plan.getSortBy() == null || plan.getSortBy().isBlank()) return "";
        return " ORDER BY " + q(plan.getSortBy()) + " " + plan.getSortOrder().getSql();
    }

    /**
     * compare 的 ORDER BY：sortBy 必须命中维度或指标之一，否则静默忽略并回退默认降序。
     * 与 query 的 aggOrderClause 对齐，避免 LLM 给出不在 SELECT 中的列名时触发
     * "column must appear in GROUP BY"。
     * / ORDER BY for compare must reference the dimension or a metric alias; anything
     * else is ignored with a default descending sort, mirroring aggOrderClause.
     */
    private String compareOrderClause(AnalysisPlan plan, String dimension, List<String> metrics) {
        String dir = plan.getSortOrder().getSql();
        String sortBy = plan.getSortBy();
        if (sortBy != null && !sortBy.isBlank()) {
            if (sortBy.equals(dimension)) return " ORDER BY dimension " + dir;
            if (metrics.contains(sortBy)) return " ORDER BY " + q(sortBy) + " " + dir;
        }
        if (metrics.isEmpty()) return "";
        return " ORDER BY " + q(metrics.get(0)) + " DESC";
    }

    /**
     * 聚合查询的 ORDER BY：引用 SELECT 中的别名，避免 GROUP BY 违规。
     * - sortBy 命中维度列 → 按 dimension 别名排序
     * - sortBy 命中指标列 → 按该指标的聚合别名（如 "订单数_count"）排序
     * - 未指定或无法匹配 → 按第一个指标的聚合别名降序（最常见的分析诉求）
     * ORDER BY for aggregate queries references SELECT aliases to avoid GROUP BY violations.
     */
    private String aggOrderClause(AnalysisPlan plan, String agg, List<String> metrics) {
        String dir = plan.getSortOrder().getSql();
        String sortBy = plan.getSortBy();

        if (sortBy != null && !sortBy.isBlank()) {
            if (plan.hasDimension() && sortBy.equals(plan.getDimension())) {
                return " ORDER BY dimension " + dir;
            }
            if (metrics.contains(sortBy)) {
                return " ORDER BY " + q(sortBy + "_" + agg.toLowerCase()) + " " + dir;
            }
        }
        // 默认：按聚合结果降序，让最大值排在最前 / Default: sort by aggregated value descending.
        if (metrics.isEmpty()) return "";
        return " ORDER BY " + q(metrics.get(0) + "_" + agg.toLowerCase()) + " DESC";
    }

    private String safeOp(String op) {
        return switch (op) {
            case ">", "<", ">=", "<=", "!=", "<>", "=" -> op;
            case "like", "LIKE" -> "LIKE";
            // 未知操作符静默改成 = 会返回错误结果；直接拒绝，让问题暴露。
            // Silently mapping an unknown operator to = produces wrong results; fail loudly instead.
            default -> throw new IllegalArgumentException("不支持的过滤操作符 / Unsupported filter operator: " + op);
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
