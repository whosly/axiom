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

    private AnalysisEngine engine;
    private Dataset dataset;

    /** 构造被测对象与固定数据集 / Build subject and a fixed dataset. */
    @BeforeEach
    void setUp() {
        engine = new AnalysisEngine(datasetRepo);
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
}
