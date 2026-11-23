package com.axiom.service;

import com.axiom.model.ColumnInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据接入服务测试 / Tests for data ingestion and type inference.
 *
 * <p>覆盖 CSV/TSV/JSON 解析与列类型推断，这是分析引擎正确性的前提。
 * Covers CSV/TSV/JSON parsing and column type inference, the basis for correct analysis.
 */
class DataIngestionServiceTest {

    private final DataIngestionService service = new DataIngestionService();

    /** CSV 解析与数值/日期类型推断 / CSV parsing with numeric and date type inference. */
    @Test
    @DisplayName("CSV 解析与类型推断")
    void parseCsv() throws Exception {
        String csv = "日期,产品,销售额,达标\n2026-01-01,产品A,1200,true\n2026-01-02,产品B,800,false\n";
        DataIngestionService.ParsedData data = service.parse(
                csv.getBytes(StandardCharsets.UTF_8), "sales.csv", "text/csv");

        assertThat(data.headers()).containsExactly("日期", "产品", "销售额", "达标");
        assertThat(data.rows()).hasSize(2);
        assertThat(data.columns()).hasSize(4);
        ColumnInfo date = data.columns().get(0);
        ColumnInfo amount = data.columns().get(2);
        ColumnInfo flag = data.columns().get(3);
        assertThat(date.getType()).isEqualTo("temporal");
        assertThat(amount.getType()).isEqualTo("numeric");
        assertThat(flag.getType()).isEqualTo("boolean");
    }

    /** TSV 解析（制表符分隔）/ TSV parsing. */
    @Test
    @DisplayName("TSV 解析")
    void parseTsv() throws Exception {
        String tsv = "a\tb\n1\t2\n3\t4";
        DataIngestionService.ParsedData data = service.parse(
                tsv.getBytes(StandardCharsets.UTF_8), "data.tsv", "text/tab-separated-values");
        assertThat(data.headers()).containsExactly("a", "b");
        assertThat(data.rows()).hasSize(2);
        assertThat(data.columns().get(0).getType()).isEqualTo("numeric");
    }

    /** JSON 数组解析 / JSON array parsing. */
    @Test
    @DisplayName("JSON 数组解析")
    void parseJson() throws Exception {
        String json = "[{\"name\":\"A\",\"value\":10},{\"name\":\"B\",\"value\":20}]";
        DataIngestionService.ParsedData data = service.parse(
                json.getBytes(StandardCharsets.UTF_8), "data.json", "application/json");
        assertThat(data.headers()).contains("name", "value");
        assertThat(data.rows()).hasSize(2);
        assertThat(data.rows().get(0)).containsKey("name");
    }

    /** 粘贴文本解析（复用 CSV 推断）/ Pasted text parsing. */
    @Test
    @DisplayName("粘贴文本解析")
    void parsePasted() throws Exception {
        String text = "x,y\n1,hello\n2,world";
        DataIngestionService.ParsedData data = service.parsePasted(text);
        assertThat(data.headers()).containsExactly("x", "y");
        assertThat(data.rows()).hasSize(2);
        assertThat(data.columns().get(1).getType()).isEqualTo("categorical");
    }

    /** 空内容返回空结构 / Empty input returns empty structure. */
    @Test
    @DisplayName("空内容处理")
    void parseEmpty() throws Exception {
        DataIngestionService.ParsedData data = service.parsePasted("");
        assertThat(data.headers()).isEmpty();
        assertThat(data.rows()).isEmpty();
    }
}
