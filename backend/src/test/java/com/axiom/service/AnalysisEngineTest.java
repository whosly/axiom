package com.axiom.service;

import com.axiom.domain.AnalysisIntent;
import com.axiom.domain.Aggregation;
import com.axiom.domain.TimeGranularity;
import com.axiom.model.AnalysisPlan;
import com.axiom.model.AnalysisResult;
import com.axiom.model.ColumnInfo;
import com.axiom.model.Dataset;
import com.axiom.repository.DatasetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分析引擎单元测试 / Unit tests for the analysis engine.
 *
 * <p>验证确定性 SQL 生成与结果结构：LLM 只产出计划，所有数值计算由 DuckDB 执行。
 * Tests focus on the plan → SQL → result mapping, not on numeric values.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisEngineTest {

    /** 仓储 mock：捕获生成的 SQL / Repository mock: capture generated SQL. */
    @Mock
    private DatasetRepository datasetRepo;

    /** 知识库 mock：提供公式注册表 / Knowledge mock: provides the formula registry. */
    @Mock
    private KnowledgeService knowledgeService;

    private AnalysisEngine engine;
    private Dataset dataset;

    /** 构造被测对象与固定数据集 / Build subject and a fixed dataset. */
    @BeforeEach
    void setUp() {
        engine = new AnalysisEngine(datasetRepo, knowledgeService);
        dataset = new Dataset();
        dataset.setId("ds-1");
        dataset.setTableName("ds_1");
        dataset.setRowCount(100);
        dataset.setColumnCount(3);
        ColumnInfo amount = new ColumnInfo();
        amount.setName("amount");
        amount.setType("numeric");
        ColumnInfo region = new ColumnInfo();
        region.setName("region");
        region.setType("categorical");
        ColumnInfo dt = new ColumnInfo();
        dt.setName("dt");
        dt.setType("temporal");
        dataset.setColumns(List.of(amount, region, dt));
    }

    /**
     * 查询意图应生成包含指标与表名的 SELECT，并写入 result.sql。
     * Query intent generates SELECT with metric and table name, recorded in result.sql.
     */
    @Test
    @DisplayName("查询：SQL 包含 SUM(amount) 与数据集表名")
    void queryGeneratesSelectWithMetric() {
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of());
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.QUERY);
        plan.setMetrics(List.of("amount"));
        plan.setAggregation(Aggregation.SUM);

        AnalysisResult result = engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        assertThat(sql.getValue()).contains("SUM", "amount", "ds_1");
        assertThat(result.getSql()).isEqualTo(sql.getValue());
    }

    /**
     * 趋势意图应生成按时间分桶的 GROUP BY，使用计划中的时间粒度格式。
     * Trend intent groups by a formatted time bucket using the plan's granularity.
     */
    @Test
    @DisplayName("趋势：SQL 含 GROUP BY 与时间粒度格式化")
    void trendGeneratesTimeBucketGroupBy() {
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of());
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.TREND);
        plan.setMetrics(List.of("amount"));
        plan.setTimeColumn("dt");
        plan.setTimeGranularity(TimeGranularity.MONTH);

        engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        assertThat(sql.getValue().toUpperCase()).contains("GROUP BY");
        assertThat(sql.getValue()).contains(TimeGranularity.MONTH.getFormat());
    }

    /** 趋势洞察应包含峰谷与波动，避免只看首尾误导 / Trend insights include peak/trough and volatility. */
    @Test
    @DisplayName("趋势：洞察含最高/最低与波动幅度")
    void trendInsightsIncludePeaks() {
        // 新 trend 的输出列名即指标名（AS "amount"）/ The trend output column is the metric name.
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of(
                Map.of("period", "2026-01", "amount", 48900.0),
                Map.of("period", "2026-03", "amount", 12450.0),
                Map.of("period", "2026-04", "amount", 66600.0),
                Map.of("period", "2026-05", "amount", 48300.0)));
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.TREND);
        plan.setMetrics(List.of("amount"));
        plan.setTimeColumn("dt");

        AnalysisResult result = engine.execute(dataset, plan);

        assertThat(result.getInsights()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.getInsights()).anySatisfy(s -> assertThat(s).contains("最高 66600.00（2026-04）"));
        assertThat(result.getInsights()).anySatisfy(s -> assertThat(s).contains("最低 12450.00（2026-03）"));
        assertThat(result.getInsights()).anySatisfy(s -> assertThat(s).contains("波动幅度"));
    }

    /**
     * 对比意图缺少维度时自动选用首个类别列，保证仍可做维度对比。
     * Compare intent falls back to the first categorical column when no dimension is given.
     */
    @Test
    @DisplayName("对比：缺少维度时自动选用类别列 region")
    void compareWithoutDimensionUsesFirstCategorical() {
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of());
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.COMPARE);
        plan.setMetrics(List.of("amount"));
        plan.setDimension(null);

        engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        assertThat(sql.getValue()).contains("GROUP BY", "region");
    }

    /**
     * 指标校验：文本/类别列不能做聚合，提前显式报错而非让 DuckDB 报
     * 晦涩的函数不匹配。/ Metric validation: aggregating a categorical column
     * fails early with a clear message instead of an obscure DuckDB error.
     */
    @Test
    @DisplayName("对比：类别列作指标显式报错")
    void compareRejectsNonNumericMetric() {
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.COMPARE);
        plan.setMetrics(List.of("region"));
        plan.setDimension("dt");

        // execute 的失败短路约定：sql 为 null 且结论携带原因 / failure contract: sql == null.
        AnalysisResult result = engine.execute(dataset, plan);
        assertThat(result.getSql()).isNull();
        assertThat(result.getConclusion()).contains("不是数值列或公式字段");
    }

    /**
     * 维度兜底跳过 ID 类列：类别列里有「订单ID」时优先选 region，
     * 否则按唯一标识分组每行一组，对比图全是噪音。
     * / Dimension fallback skips identifier-like columns in favor of region.
     */
    @Test
    @DisplayName("对比：维度兜底跳过 ID 类类别列")
    void compareDimensionFallbackSkipsIdColumn() {
        ColumnInfo orderId = new ColumnInfo();
        orderId.setName("订单ID");
        orderId.setType("categorical");
        List<ColumnInfo> existing = dataset.getColumns();
        dataset.setColumns(List.of(existing.get(0), orderId, existing.get(1), existing.get(2)));

        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of());
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.COMPARE);
        plan.setMetrics(List.of("amount"));
        plan.setDimension(null);

        engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        assertThat(sql.getValue()).contains("GROUP BY", "region");
        assertThat(sql.getValue()).doesNotContain("订单ID");
    }

    /**
     * 分组聚合查询的 ORDER BY 必须引用聚合别名，否则 DuckDB 报
     * "column must appear in GROUP BY"。回归防护：sortBy=指标列时按别名排序。
     * Regression guard: grouped aggregate ORDER BY must reference the alias, not the raw column.
     */
    @Test
    @DisplayName("分组查询：ORDER BY 引用聚合别名而非原始列")
    void groupedQueryOrdersByAggregateAlias() {
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of());
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.QUERY);
        plan.setDimension("region");
        plan.setMetrics(List.of("amount"));
        plan.setAggregation(Aggregation.COUNT);
        plan.setSortBy("amount");

        engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        String query = sql.getValue().toUpperCase();
        assertThat(query).contains("ORDER BY \"AMOUNT_COUNT\"");
        assertThat(query).doesNotContain("ORDER BY \"AMOUNT\"");
    }

    /**
     * 概览意图应调用 stats() 汇总数值列，结果含 summary 数据。
     * Summary intent calls stats() over numeric columns and returns summary rows.
     */
    @Test
    @DisplayName("概览：调用 stats 并返回数值列统计")
    void summaryReturnsStats() {
        when(datasetRepo.stats(anyString(), anyString())).thenReturn(Map.of("avg", 10, "min", 1, "max", 20));
        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.SUMMARY);

        AnalysisResult result = engine.execute(dataset, plan);

        verify(datasetRepo).stats("ds_1", "amount");
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0)).containsKey("amount");
    }

    /**
     * 公式指标：命中知识库公式时按 aggregate-then-compute 编译 SQL，
     * 数值完全由引擎确定性计算。/ Formula metrics compile deterministically via
     * aggregate-then-compute when a knowledge formula matches.
     */
    @Test
    @DisplayName("公式指标：query 按公式编译 SQL")
    void formulaMetricCompiles() {
        when(knowledgeService.formulaIndex()).thenReturn(Map.of(
                "毛利", "amount * 2"));
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of(
                Map.of("dimension", "华东", "毛利_sum", 1000)));

        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.QUERY);
        plan.setMetrics(List.of("毛利"));
        plan.setAggregation(Aggregation.SUM);
        plan.setDimension("地区");

        engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        // 毛利 → (SUM("销售额") - SUM("成本"))，而非 SUM("毛利")
        // / The derived metric expands to its formula, never SUM("毛利").
        assertThat(sql.getValue()).contains("(SUM(\"amount\") * 2) AS \"毛利_sum\"");
        assertThat(sql.getValue()).doesNotContain("SUM(\"毛利\")");
    }

    /** 公式循环引用必须在执行期显式报错 / Circular formulas must fail loudly at execution. */
    @Test
    @DisplayName("公式循环引用显式报错")
    void circularFormulaFails() {
        when(knowledgeService.formulaIndex()).thenReturn(Map.of(
                "A", "B + 1",
                "B", "A * 2"));

        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.QUERY);
        plan.setMetrics(List.of("A"));
        plan.setAggregation(Aggregation.SUM);

        AnalysisResult result = engine.execute(dataset, plan);
        // 引擎的失败短路约定：sql 为 null 且结论携带原因 / Failure contract: sql == null.
        assertThat(result.getSql()).isNull();
        assertThat(result.getConclusion()).contains("循环引用");
    }

    /**
     * anomaly + 公式指标：WHERE 不能引用公式标题（该列不存在），
     * 必须以 1=1 占位 / anomaly with a formula metric must not null-check the
     * formula title as if it were a physical column.
     */
    @Test
    @DisplayName("公式指标：anomaly 的 WHERE 不引用公式标题列")
    void anomalyFormulaSkipsTitleNullCheck() {
        when(knowledgeService.formulaIndex()).thenReturn(Map.of(
                "毛利", "amount * 2"));
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of(
                Map.of("item", "华东", "value", 1000)));

        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.ANOMALY);
        plan.setMetrics(List.of("毛利"));
        plan.setAggregation(Aggregation.SUM);
        plan.setDimension("region");

        engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        // 修复前生成 WHERE "毛利" IS NOT NULL —— 公式标题不是物理列，SQL 必然失败
        // / Before the fix this generated WHERE "毛利" IS NOT NULL — a non-existent column.
        assertThat(sql.getValue()).doesNotContain("\"毛利\" IS NOT NULL");
        assertThat(sql.getValue()).contains("(SUM(\"amount\") * 2) AS value");
        assertThat(sql.getValue()).contains("GROUP BY");
    }

    /** anomaly 无分组 + 公式：行级计算路径 / anomaly row-level formula without grouping. */
    @Test
    @DisplayName("公式指标：anomaly 行级路径不引用公式标题列")
    void anomalyRowLevelFormulaSkipsTitleNullCheck() {
        when(knowledgeService.formulaIndex()).thenReturn(Map.of(
                "毛利", "amount * 2"));
        when(datasetRepo.query(anyString(), anyInt())).thenReturn(List.of(
                Map.of("item", "1", "value", 100)));

        AnalysisPlan plan = new AnalysisPlan();
        plan.setIntent(AnalysisIntent.ANOMALY);
        plan.setMetrics(List.of("毛利"));
        plan.setAggregation(Aggregation.SUM);

        engine.execute(dataset, plan);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(datasetRepo).query(sql.capture(), anyInt());
        assertThat(sql.getValue()).doesNotContain("\"毛利\" IS NOT NULL");
        // 行级公式叶节点输出裸列 / Row-level formula leaves emit bare columns.
        assertThat(sql.getValue()).contains("(\"amount\" * 2) AS value");
    }
}
