package com.axiom.service;

import com.axiom.model.ColumnInfo;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Parses uploaded files / pasted text into a uniform tabular structure. */
@Service
public class DataIngestionService {

    public record ParsedData(List<String> headers, List<Map<String, Object>> rows, List<ColumnInfo> columns) {}

    private static final Set<String> DATE_PATTERNS = Set.of(
            "yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy", "dd/MM/yyyy",
            "yyyy-MM", "yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss"
    );
    /** 与 DATE_PATTERNS 一一对应的预构建格式化器（线程安全，类型推断全程复用）/ Prebuilt formatters matching DATE_PATTERNS. */
    private static final List<java.time.format.DateTimeFormatter> DATE_FORMATTERS = DATE_PATTERNS.stream()
            .map(java.time.format.DateTimeFormatter::ofPattern)
            .toList();
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$");

    public ParsedData parse(byte[] bytes, String fileName, String mimeType) throws IOException {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return parseExcel(bytes);
        }
        if (lower.endsWith(".json")) {
            return parseJson(bytes);
        }
        return parseCsv(new String(bytes, StandardCharsets.UTF_8));
    }

    public ParsedData parsePasted(String text) throws IOException {
        return parseCsv(text);
    }

    // ---- CSV / TSV ----

    private ParsedData parseCsv(String text) throws IOException {
        String sample = text.substring(0, Math.min(8192, text.length()));
        char delimiter = detectDelimiter(sample);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setDelimiter(delimiter)
                .build();

        try (CSVParser parser = format.parse(new StringReader(text))) {
            List<String> headers = new ArrayList<>(parser.getHeaderNames());
            sanitizeHeaders(headers);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    String value = i < record.size() ? record.get(i) : "";
                    row.put(headers.get(i), value.isEmpty() ? null : value);
                }
                rows.add(row);
            }
            return buildResult(headers, rows);
        }
    }

    private char detectDelimiter(String sample) {
        String firstLine = sample.lines().findFirst().orElse("");
        char best = ',';
        int max = 0;
        for (char c : new char[]{',', '\t', ';', '|'}) {
            int count = 0;
            for (int i = 0; i < firstLine.length(); i++) if (firstLine.charAt(i) == c) count++;
            if (count > max) { max = count; best = c; }
        }
        return best;
    }

    // ---- JSON (array of objects) ----

    private ParsedData parseJson(byte[] bytes) throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(bytes);
        if (!root.isArray() || root.isEmpty()) throw new IOException("JSON 必须是非空的对象数组");

        LinkedHashSet<String> headerSet = new LinkedHashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            node.fieldNames().forEachRemaining(headerSet::add);
        }
        List<String> headers = new ArrayList<>(headerSet);
        sanitizeHeaders(headers);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String h : headers) {
                com.fasterxml.jackson.databind.JsonNode v = node.get(h);
                row.put(h, v == null || v.isNull() ? null : v.isTextual() ? v.asText() : v.toString());
            }
            rows.add(row);
        }
        return buildResult(headers, rows);
    }

    // ---- Excel ----

    /**
     * 流式解析行数上限：超过即报错而非静默截断——截断会让分析口径失真，
     * 且用户毫无感知。200k 行 × 20 列字符串约百余 MB，处于可控范围。
     * / Streaming row cap: fail loudly instead of silently truncating, which
     * distorts analytics without the user ever noticing.
     */
    private static final int MAX_XLSX_ROWS = 200_000;

    /** 行数护栏专用信号异常：SAX 无干净的中止 API，用 RuntimeException 中断解析。 */
    private static final class RowLimitReached extends RuntimeException {}

    private ParsedData parseExcel(byte[] bytes) throws IOException {
        // 按魔数分流而非扩展名（扩展名可被改名误导）：
        // - OLE2(.xls)：HSSF 行数硬上限 65536，DOM 内存有界，走 WorkbookFactory；
        // - ZIP(.xlsx)：无行数上限，XSSF DOM 随文件膨胀 10-50 倍内存，必须走 SAX 流式。
        // / Dispatch by magic bytes, not extension (extensions can be misleading):
        // OLE2 (.xls) has a hard 65536-row cap so DOM is bounded; ZIP (.xlsx) has no
        // cap and an XSSF DOM inflates memory 10-50x, so stream it with SAX.
        try (InputStream probe = new ByteArrayInputStream(bytes)) {
            org.apache.poi.poifs.filesystem.FileMagic magic =
                    org.apache.poi.poifs.filesystem.FileMagic.valueOf(probe);
            if (magic == org.apache.poi.poifs.filesystem.FileMagic.OLE2) {
                return parseXlsDom(bytes);
            }
            return parseXlsxStreaming(bytes, MAX_XLSX_ROWS);
        }
    }

    /** .xls 走 DOM：行数有硬上限，WorkbookFactory 按 HSSF 打开。 */
    private ParsedData parseXlsDom(byte[] bytes) throws IOException {
        try (org.apache.poi.ss.usermodel.Workbook wb =
                     org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) throw new IOException("Excel 文件无工作表");

            List<String> headers = new ArrayList<>();
            org.apache.poi.ss.usermodel.Row headerRow = sheet.getRow(0);
            if (headerRow == null) throw new IOException("Excel 首行为空");
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                headers.add(cellToString(headerRow.getCell(i)));
            }
            sanitizeHeaders(headers);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> map = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    String v = cellToString(row.getCell(c));
                    map.put(headers.get(c), v.isEmpty() ? null : v);
                }
                rows.add(map);
            }
            return buildResult(headers, rows);
        }
    }

    /**
     * .xlsx 走 SAX 事件流式（XSSFReader + XSSFSheetXMLHandler）：逐行回调装配，
     * 不构建整簿 DOM，内存占用只随「表头 + 当前行」增长而非整个文件。
     * / Stream .xlsx via the SAX event model: rows are assembled from callbacks,
     * memory scales with header + current row instead of the whole workbook DOM.
     */
    /** .xlsx 流式解析；包内可见以便测试注入小行数上限触发护栏。 */
    ParsedData parseXlsxStreaming(byte[] bytes, int maxRows) throws IOException {
        try (org.apache.poi.openxml4j.opc.OPCPackage pkg =
                     org.apache.poi.openxml4j.opc.OPCPackage.open(new ByteArrayInputStream(bytes))) {
            XSSFReader reader = new XSSFReader(pkg);
            org.apache.poi.xssf.model.StylesTable styles = reader.getStylesTable();
            if (styles == null) throw new IOException("Excel 无样式表");
            org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable strings =
                    new org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable(pkg);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) throw new IOException("Excel 文件无工作表");

            StreamingSheetHandler collector = new StreamingSheetHandler(maxRows);
            try (InputStream sheetStream = sheets.next()) {
                // 日期单元格对齐 DOM 路径（isCellDateFormatted → ISO 输出），保证
                // 类型推断在两条解析路径上行为一致，趋势分析不因路径而异。
                // / Emit ISO dates for date cells so type inference behaves identically
                // on both parse paths (DOM uses isCellDateFormatted).
                org.apache.poi.ss.usermodel.DataFormatter formatter =
                        new org.apache.poi.ss.usermodel.DataFormatter() {
                            @Override
                            public String formatRawCellContents(double value, int formatIndex, String formatString) {
                                if (org.apache.poi.ss.usermodel.DateUtil.isADateFormat(formatIndex, formatString)) {
                                    try {
                                        java.time.LocalDateTime dt =
                                                org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(value);
                                        return dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                                                ? dt.toLocalDate().toString() : dt.toString();
                                    } catch (IllegalArgumentException ignored) {
                                        // 非法序列号按普通数值输出 / fall through to plain numeric.
                                    }
                                }
                                return super.formatRawCellContents(value, formatIndex, formatString);
                            }
                        };
                org.xml.sax.XMLReader parser = org.apache.poi.util.XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, strings, collector, formatter, false));
                try {
                    parser.parse(new org.xml.sax.InputSource(sheetStream));
                } catch (RowLimitReached stop) {
                    throw new IOException("Excel 超过最大导入行数 " + MAX_XLSX_ROWS + "，请拆分文件后再上传");
                }
            }
            return collector.result();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Excel 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * SAX 行收集器：首行为表头，其后逐行装配成 map（与 DOM 路径同构）。
     * XSSFSheetXMLHandler 只对存在的单元格回调，稀疏行按列号补 null。
     */
    private final class StreamingSheetHandler
            implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final int maxRows;
        private List<String> headers;
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final Map<Integer, String> current = new HashMap<>();
        private boolean headerDone = false;

        StreamingSheetHandler(int maxRows) { this.maxRows = maxRows; }

        @Override public void startRow(int rowNum) { current.clear(); }

        @Override public void endRow(int rowNum) {
            if (!headerDone) {
                // 表头宽度取出现过的最大列号 +1，空缺表头补 col_N。
                // / Header width = max seen column index + 1; gaps become col_N.
                int width = current.keySet().stream().max(Integer::compare).orElse(-1) + 1;
                if (width == 0) throw new IllegalStateException("Excel 首行为空");
                headers = new ArrayList<>(width);
                for (int c = 0; c < width; c++) {
                    String h = current.get(c);
                    headers.add(h == null || h.isEmpty() ? "col_" + c : h);
                }
                sanitizeHeaders(headers);
                headerDone = true;
                return;
            }
            if (rows.size() >= maxRows) throw new RowLimitReached();
            Map<String, Object> map = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String v = current.get(c);
                map.put(headers.get(c), v == null || v.isEmpty() ? null : v);
            }
            rows.add(map);
        }

        @Override public void cell(String cellReference, String formattedValue,
                                   org.apache.poi.xssf.usermodel.XSSFComment comment) {
            if (cellReference == null) return;
            int col = new org.apache.poi.ss.util.CellReference(cellReference).getCol();
            String v = formattedValue == null ? "" : formattedValue.trim();
            // SAX 对布尔单元格输出 "TRUE"/"FALSE"，与 DOM 路径的 "true"/"false" 归一，
            // 否则同一文件两条路径类型推断结果不同。/ Normalize SAX boolean casing
            // to match the DOM path so type inference is path-independent.
            if ("TRUE".equalsIgnoreCase(v)) v = "true";
            else if ("FALSE".equalsIgnoreCase(v)) v = "false";
            current.put(col, v);
        }

        /** 供上层组装类型推断结果 / Expose collected rows for buildResult. */        ParsedData result() {
            if (!headerDone) throw new IllegalStateException("Excel 首行为空");
            return buildResult(headers, rows);
        }
    }

    private String cellToString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                // Excel 日期在 POI 里也是 NUMERIC（序列号 45000 之类），必须先判断日期格式，
                // 否则日期列被导入成整数、时间列类型推断失效、趋势分析直接报废。
                // / Excel dates are NUMERIC cells (serial numbers) in POI; check the date
                // format first or date columns import as integers and break trend analysis.
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    java.time.LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    yield dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                            ? dt.toLocalDate().toString()
                            : dt.toString();
                }
                yield v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf(cell.getStringCellValue()); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    // ---- Shared: type inference & column metadata ----

    private ParsedData buildResult(List<String> headers, List<Map<String, Object>> rows) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (String h : headers) {
            List<String> samples = new ArrayList<>();
            boolean allNumeric = true, allTemporal = true, allBoolean = true;
            int checked = 0;

            for (Map<String, Object> row : rows) {
                Object raw = row.get(h);
                if (raw == null || raw.toString().isBlank()) continue;
                String v = raw.toString().trim();
                if (samples.size() < 3 && !samples.contains(v)) samples.add(v);
                checked++;
                if (!NUMERIC.matcher(v).matches()) allNumeric = false;
                if (!isTemporal(v)) allTemporal = false;
                if (!isBoolean(v)) allBoolean = false;
            }

            String type = "categorical";
            if (checked > 0) {
                if (allNumeric) type = "numeric";
                else if (allTemporal) type = "temporal";
                else if (allBoolean) type = "boolean";
            }
            columns.add(new ColumnInfo(h, type, String.join(" | ", samples)));
        }
        return new ParsedData(headers, rows, columns);
    }

    private boolean isTemporal(String v) {
        // DateTimeFormatter 线程安全且构造昂贵，预构建复用；原先对每个单元格 × 8 个
        // pattern 重复构造，大文件类型推断因此成为解析瓶颈。
        // / DateTimeFormatter is thread-safe and costly to build; reuse prebuilt instances.
        // Previously a formatter was created per cell per pattern (8x per cell), making
        // type inference the ingestion bottleneck on large files.
        for (java.time.format.DateTimeFormatter f : DATE_FORMATTERS) {
            try {
                java.time.LocalDate.parse(v, f);
                return true;
            } catch (DateTimeParseException ignored) {}
            catch (IllegalArgumentException ignored) {}
        }
        try { java.time.Instant.parse(v); return true; } catch (Exception ignored) {}
        return false;
    }

    private boolean isBoolean(String v) {
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")
                || v.equals("1") || v.equals("0") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("no");
    }

    private void sanitizeHeaders(List<String> headers) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h == null || h.isBlank()) h = "col_" + i;
            h = h.trim().replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");
            if (h.matches("^\\d.*")) h = "c_" + h;
            String base = h;
            int suffix = 2;
            while (seen.contains(h)) h = base + "_" + suffix++;
            headers.set(i, h);
            seen.add(h);
        }
    }
}
