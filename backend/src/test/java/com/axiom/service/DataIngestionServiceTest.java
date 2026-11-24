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

    /**
     * .xlsx 流式解析：日期/数值/布尔/公式单元格与 DOM 路径行为对齐，
     * 类型推断（temporal/numeric/boolean）不因 SAX 路径而变化。
     */
    @Test
    @DisplayName("xlsx 流式解析与类型推断")
    void parseXlsxStreaming() throws Exception {
        byte[] bytes;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("数据");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("日期");
            header.createCell(1).setCellValue("销售额");
            header.createCell(2).setCellValue("达标");
            header.createCell(3).setCellValue("毛利");
            for (int r = 1; r <= 3; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                org.apache.poi.ss.usermodel.Cell date = row.createCell(0);
                date.setCellValue(java.time.LocalDate.of(2026, 1, r).toString());
                org.apache.poi.ss.usermodel.CellStyle dateStyle = wb.createCellStyle();
                dateStyle.setDataFormat(
                        wb.createDataFormat().getFormat("yyyy-mm-dd"));
                date.setCellStyle(dateStyle);
                row.createCell(1).setCellValue(1200.5 * r);
                row.createCell(2).setCellValue(r % 2 == 0);
                // 公式单元格：SAX 路径取缓存结果 / formula cell: cached result via SAX.
                org.apache.poi.ss.usermodel.Cell f = row.createCell(3);
                f.setCellFormula("B" + (r + 1) + "*0.2");
            }
            // SAX 路径读取公式缓存值，写入前必须求值 / SAX reads cached formula values; evaluate first.
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (var out = new java.io.ByteArrayOutputStream()) {
                wb.write(out);
                bytes = out.toByteArray();
            }
        }
        DataIngestionService.ParsedData data = service.parse(bytes, "sales.xlsx", "application/octet-stream");
        assertThat(data.headers()).containsExactly("日期", "销售额", "达标", "毛利");
        assertThat(data.rows()).hasSize(3);
        // 第一行数据 / first data row.
        Map<String, Object> row0 = data.rows().get(0);
        assertThat(row0.get("日期")).isEqualTo("2026-01-01");
        assertThat(row0.get("销售额")).isEqualTo("1200.5");
        assertThat(row0.get("达标")).isEqualTo("false");
        assertThat(row0.get("毛利")).isEqualTo("240.1");
        // 类型推断与 CSV 路径一致 / type inference identical to the CSV path.
        assertThat(data.columns().get(0).getType()).isEqualTo("temporal");
        assertThat(data.columns().get(1).getType()).isEqualTo("numeric");
        assertThat(data.columns().get(2).getType()).isEqualTo("boolean");
    }

    /** .xls（HSSF）走 DOM 路径仍可用 / legacy .xls keeps working through the DOM path. */
    @Test
    @DisplayName("xls 老格式 DOM 解析")
    void parseXlsDom() throws Exception {
        byte[] bytes;
        try (org.apache.poi.hssf.usermodel.HSSFWorkbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("S1");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("名称");
            header.createCell(1).setCellValue("数量");
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("产品A");
            row.createCell(1).setCellValue(42);
            try (var out = new java.io.ByteArrayOutputStream()) {
                wb.write(out);
                bytes = out.toByteArray();
            }
        }
        DataIngestionService.ParsedData data = service.parse(bytes, "old.xls", "application/vnd.ms-excel");
        assertThat(data.headers()).containsExactly("名称", "数量");
        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0).get("数量")).isEqualTo("42");
        assertThat(data.columns().get(1).getType()).isEqualTo("numeric");
    }

    /** 流式行数护栏：超限报错而非静默截断 / row cap fails loudly instead of truncating. */
    @Test
    @DisplayName("xlsx 行数上限护栏")
    void parseXlsxRowLimit() throws Exception {
        byte[] bytes;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("S1");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("v");
            for (int r = 1; r <= 10; r++) {
                sheet.createRow(r).createCell(0).setCellValue(r);
            }
            try (var out = new java.io.ByteArrayOutputStream()) {
                wb.write(out);
                bytes = out.toByteArray();
            }
        }
        // 同一文件：上限 5 报错，上限 20 正常通过 / cap 5 fails, cap 20 succeeds.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.parseXlsxStreaming(bytes, 5))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("最大导入行数");
        DataIngestionService.ParsedData ok = service.parseXlsxStreaming(bytes, 20);
        assertThat(ok.rows()).hasSize(10);
    }
}
