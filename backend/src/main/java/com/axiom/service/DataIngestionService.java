package com.axiom.service;

import com.axiom.model.ColumnInfo;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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

    private ParsedData parseExcel(byte[] bytes) throws IOException {
        // WorkbookFactory 按文件魔数自动识别 HSSF(.xls) 与 XSSF(.xlsx)：
        // 此前固定用 XSSFWorkbook 打开，老式 .xls 必然抛 InvalidFormatException。
        // / WorkbookFactory detects HSSF(.xls) vs XSSF(.xlsx) by magic bytes; a hard-coded
        // XSSFWorkbook always failed on legacy .xls files.
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

    private String cellToString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
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
