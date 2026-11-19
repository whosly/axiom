package com.dataagent.repository;

import com.dataagent.model.KnowledgeSource;
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
 * 知识来源元数据存储 / Knowledge source metadata repository.
 */
@Slf4j
@Repository
public class KnowledgeSourceRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeSourceRepository(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_sources (
                id VARCHAR(128) PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                type VARCHAR(32) NOT NULL,
                description TEXT,
                entry_count INTEGER DEFAULT 0,
                status VARCHAR(16) DEFAULT 'active',
                created_at VARCHAR(64),
                updated_at VARCHAR(64)
            )
        """);
        log.info("[KnowledgeSource] schema ready");
    }

    public void save(KnowledgeSource s) {
        jdbc.update("DELETE FROM knowledge_sources WHERE id = ?", s.getId());
        jdbc.update("""
            INSERT INTO knowledge_sources
              (id, name, type, description, entry_count, status, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,
            s.getId(), s.getName(), s.getType(), s.getDescription(), s.getEntryCount(),
            s.getStatus(), str(s.getCreatedAt()), str(s.getUpdatedAt()));
    }

    public List<KnowledgeSource> listAll() {
        return jdbc.query("SELECT * FROM knowledge_sources ORDER BY updated_at DESC", this::mapRow);
    }

    public KnowledgeSource findById(String id) {
        List<KnowledgeSource> list = jdbc.query("SELECT * FROM knowledge_sources WHERE id = ?", this::mapRow, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM knowledge_sources WHERE id = ?", id);
    }

    /** 更新条目数 / Update entry count. */
    public void updateCount(String id, int count) {
        jdbc.update("UPDATE knowledge_sources SET entry_count = ?, updated_at = ? WHERE id = ?",
                count, LocalDateTime.now().toString(), id);
    }

    private KnowledgeSource mapRow(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeSource s = new KnowledgeSource();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setType(rs.getString("type"));
        s.setDescription(rs.getString("description"));
        s.setEntryCount(rs.getInt("entry_count"));
        s.setStatus(rs.getString("status"));
        s.setCreatedAt(parse(rs.getString("created_at")));
        s.setUpdatedAt(parse(rs.getString("updated_at")));
        return s;
    }

    private static LocalDateTime parse(String v) {
        if (v == null || v.isBlank()) return null;
        try { return LocalDateTime.parse(v); } catch (Exception e) { return null; }
    }
    private static String str(LocalDateTime v) { return v == null ? LocalDateTime.now().toString() : v.toString(); }
}
