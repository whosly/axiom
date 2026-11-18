package com.dataagent.repository;

import com.dataagent.model.ColumnInfo;
import com.dataagent.service.DataIngestionService.ParsedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Slf4j
@Repository
public class DatasetRepository {

    private final JdbcTemplate jdbc;

    public DatasetRepository(@Qualifier("duckJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Create a DuckDB table for a dataset and bulk-load its rows. */
    public void createTable(String tableName, ParsedData data) {
        StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE \"" + tableName + "\" (");
        for (int i = 0; i < data.headers().size(); i++) {
            if (i > 0) ddl.append(", ");
            ColumnInfo col = data.columns().get(i);
            String sqlType = switch (col.getType()) {
                case "numeric" -> "DOUBLE";
                case "temporal" -> "VARCHAR";
                case "boolean" -> "VARCHAR";
                default -> "VARCHAR";
            };
            ddl.append("\"").append(col.getName()).append("\" ").append(sqlType);
        }
        ddl.append(")");
        jdbc.execute(ddl.toString());

        if (data.rows().isEmpty()) return;

        String placeholders = String.join(",", Collections.nCopies(data.headers().size(), "?"));
        String insert = "INSERT INTO \"" + tableName + "\" VALUES (" + placeholders + ")";
        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> row : data.rows()) {
            Object[] values = new Object[data.headers().size()];
            for (int i = 0; i < data.headers().size(); i++) {
                Object v = row.get(data.headers().get(i));
                if (v == null) values[i] = null;
                else if ("numeric".equals(data.columns().get(i).getType())) {
                    try { values[i] = Double.parseDouble(v.toString()); }
                    catch (NumberFormatException e) { values[i] = null; }
                } else values[i] = v.toString();
            }
            batch.add(values);
        }
        jdbc.batchUpdate(insert, batch);
        log.info("[DuckDB] created {} with {} rows", tableName, batch.size());
    }

    public void dropTable(String tableName) {
        jdbc.execute("DROP TABLE IF EXISTS \"" + tableName + "\"");
    }

    /** Execute a read-only query and return rows as list of maps. */
    public List<Map<String, Object>> query(String sql, int limit) {
        String safeSql = sql.trim().replaceAll(";\\s*$", "");
        if (!safeSql.toLowerCase().startsWith("select") && !safeSql.toLowerCase().startsWith("with")) {
            throw new IllegalArgumentException("Only SELECT queries are allowed");
        }
        String wrapped = "SELECT * FROM (" + safeSql + ") LIMIT " + Math.max(1, Math.min(limit, 10000));
        return jdbc.queryForList(wrapped);
    }

    /** Preview first N rows. */
    public List<Map<String, Object>> preview(String tableName, int n) {
        return jdbc.queryForList("SELECT * FROM \"" + tableName + "\" LIMIT " + Math.max(1, Math.min(n, 1000)));
    }

    /** Basic descriptive statistics for a numeric column. */
    public Map<String, Object> stats(String tableName, String column) {
        String sql = """
            SELECT COUNT("%1$s") AS cnt, AVG("%1$s") AS avg, MIN("%1$s") AS min,
                   MAX("%1$s") AS max, STDDEV_SAMP("%1$s") AS stddev,
                   MEDIAN("%1$s") AS median, SUM("%1$s") AS sum
            FROM "%2$s"
            """.formatted(escapeIdent(column), escapeIdent(tableName));
        return jdbc.queryForMap(sql);
    }

    private String escapeIdent(String s) {
        return s.replace("\"", "\"\"");
    }
}
