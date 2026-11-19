package com.dataagent.repository;

import com.dataagent.model.ExternalDatasource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 外部数据源元数据存储 / External datasource metadata repository.
 */
@Slf4j
@Repository
public class ExternalDatasourceRepository {

    private final JdbcTemplate jdbc;

    public ExternalDatasourceRepository(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化表 / Initialize table. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS external_datasources (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                type VARCHAR(32) NOT NULL,
                jdbc_url TEXT NOT NULL,
                username VARCHAR(255),
                encrypted_password TEXT,
                status VARCHAR(16) DEFAULT 'active',
                last_tested_at VARCHAR(64),
                created_at VARCHAR(64)
            )
        """);
        log.info("[ExternalDatasource] schema ready");
    }

    public void save(ExternalDatasource ds) {
        jdbc.update("DELETE FROM external_datasources WHERE id = ?", ds.getId());
        jdbc.update("""
            INSERT INTO external_datasources
              (id, name, type, jdbc_url, username, encrypted_password, status, last_tested_at, created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,
            ds.getId(), ds.getName(), ds.getType(), ds.getJdbcUrl(), ds.getUsername(),
            ds.getEncryptedPassword(), ds.getStatus(), str(ds.getLastTestedAt()), str(ds.getCreatedAt()));
    }

    public List<ExternalDatasource> listAll() {
        return jdbc.query("SELECT * FROM external_datasources ORDER BY created_at DESC", this::mapRow);
    }

    public ExternalDatasource findById(String id) {
        List<ExternalDatasource> list = jdbc.query(
                "SELECT * FROM external_datasources WHERE id = ?", this::mapRow, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM external_datasources WHERE id = ?", id);
    }

    private ExternalDatasource mapRow(ResultSet rs, int rowNum) throws SQLException {
        ExternalDatasource ds = new ExternalDatasource();
        ds.setId(rs.getString("id"));
        ds.setName(rs.getString("name"));
        ds.setType(rs.getString("type"));
        ds.setJdbcUrl(rs.getString("jdbc_url"));
        ds.setUsername(rs.getString("username"));
        ds.setEncryptedPassword(rs.getString("encrypted_password"));
        ds.setStatus(rs.getString("status"));
        ds.setLastTestedAt(parse(rs.getString("last_tested_at")));
        ds.setCreatedAt(parse(rs.getString("created_at")));
        return ds;
    }

    private static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s); } catch (Exception e) { return null; }
    }

    private static String str(LocalDateTime t) {
        return t == null ? LocalDateTime.now().toString() : t.toString();
    }
}
