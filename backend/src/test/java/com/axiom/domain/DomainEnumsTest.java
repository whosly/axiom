package com.axiom.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领域枚举单元测试 / Unit tests for domain enums.
 *
 * <p>枚举的 from() 是 LLM 计划反序列化的入口，容错行为（null/非法值回退）必须稳定。
 * Enum from() methods are deserialization entry points for LLM plans; their fallback
 * behavior on null/invalid values must be stable.
 */
class DomainEnumsTest {

    /** AnalysisIntent.from 容错回退 QUERY / Invalid intent falls back to QUERY. */
    @Test
    @DisplayName("AnalysisIntent.from 容错")
    void analysisIntentFrom() {
        assertThat(AnalysisIntent.from("trend")).isEqualTo(AnalysisIntent.TREND);
        assertThat(AnalysisIntent.from("TREND")).isEqualTo(AnalysisIntent.TREND);
        assertThat(AnalysisIntent.from(null)).isEqualTo(AnalysisIntent.QUERY);
        assertThat(AnalysisIntent.from("unknown")).isEqualTo(AnalysisIntent.QUERY);
        assertThat(AnalysisIntent.TREND.getValue()).isEqualTo("trend");
        assertThat(AnalysisIntent.SUMMARY.getLabel()).isEqualTo("数据概览");
    }

    /** ChartType.from 容错回退 BAR / Invalid chart type falls back to BAR. */
    @Test
    @DisplayName("ChartType.from 容错")
    void chartTypeFrom() {
        assertThat(ChartType.from("line")).isEqualTo(ChartType.LINE);
        assertThat(ChartType.from(null)).isEqualTo(ChartType.BAR);
        assertThat(ChartType.from("unknown")).isEqualTo(ChartType.BAR);
        assertThat(ChartType.PIE.getValue()).isEqualTo("pie");
    }

    /** Aggregation.from 支持别名 average 与 SQL 名 / Aggregation.from supports aliases. */
    @Test
    @DisplayName("Aggregation.from 别名与回退")
    void aggregationFrom() {
        assertThat(Aggregation.from("avg")).isEqualTo(Aggregation.AVG);
        assertThat(Aggregation.from("AVG")).isEqualTo(Aggregation.AVG);
        assertThat(Aggregation.from("SUM")).isEqualTo(Aggregation.SUM);
        assertThat(Aggregation.from("average")).isEqualTo(Aggregation.AVG);
        assertThat(Aggregation.from(null)).isEqualTo(Aggregation.SUM);
        assertThat(Aggregation.from("unknown")).isEqualTo(Aggregation.SUM);
        assertThat(Aggregation.MAX.getSql()).isEqualTo("MAX");
    }

    /** SortOrder.from 容错回退 DESC / Invalid sort order falls back to DESC. */
    @Test
    @DisplayName("SortOrder.from 容错")
    void sortOrderFrom() {
        assertThat(SortOrder.from("asc")).isEqualTo(SortOrder.ASC);
        assertThat(SortOrder.from("ASC")).isEqualTo(SortOrder.ASC);
        assertThat(SortOrder.from(null)).isEqualTo(SortOrder.DESC);
        assertThat(SortOrder.from("unknown")).isEqualTo(SortOrder.DESC);
        assertThat(SortOrder.ASC.getSql()).isEqualTo("ASC");
        assertThat(SortOrder.DESC.getSql()).isEqualTo("DESC");
    }

    /** TimeGranularity.from 支持 value 与枚举名，回退 MONTH / Granularity from supports value and name. */
    @Test
    @DisplayName("TimeGranularity.from 与 periodSql")
    void timeGranularity() {
        assertThat(TimeGranularity.from("day")).isEqualTo(TimeGranularity.DAY);
        assertThat(TimeGranularity.from("MONTH")).isEqualTo(TimeGranularity.MONTH);
        assertThat(TimeGranularity.from(null)).isEqualTo(TimeGranularity.MONTH);
        assertThat(TimeGranularity.from("unknown")).isEqualTo(TimeGranularity.MONTH);
        String sql = TimeGranularity.MONTH.periodSql("\"dt\"");
        assertThat(sql).contains("strftime", "DATE_TRUNC", "month", "%Y-%m", "\"dt\"");
    }
}
