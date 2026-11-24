package com.axiom.repository;

import com.axiom.model.ChatMessage;
import com.axiom.model.ChatSession;
import com.axiom.model.Dataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Repository
public class MetadataRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MetadataRepository(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS datasets (
                id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                file_name VARCHAR(255),
                file_type VARCHAR(32),
                row_count INTEGER DEFAULT 0,
                column_count INTEGER DEFAULT 0,
                table_name VARCHAR(128) NOT NULL,
                columns_json TEXT,
                created_at VARCHAR(64),
                updated_at VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                id VARCHAR(64) PRIMARY KEY,
                title VARCHAR(255) NOT NULL,
                dataset_id VARCHAR(64),
                model VARCHAR(64),
                created_at VARCHAR(64),
                updated_at VARCHAR(64)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS messages (
                id VARCHAR(64) PRIMARY KEY,
                session_id VARCHAR(64) NOT NULL,
                role VARCHAR(16) NOT NULL,
                content TEXT,
                model VARCHAR(64),
                payload_json TEXT,
                created_at VARCHAR(64)
            )
        """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id)");
        log.info("[MetadataRepository] schema ready");
    }

    private static String now() { return LocalDateTime.now().toString(); }

    // ---- datasets ----

    @org.springframework.transaction.annotation.Transactional
    public void saveDataset(Dataset d, String columnsJson) {
        jdbc.update("DELETE FROM datasets WHERE id = ?", d.getId());
        jdbc.update("""
            INSERT INTO datasets (id, name, file_name, file_type, row_count, column_count,
                                  table_name, columns_json, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """, d.getId(), d.getName(), d.getFileName(), d.getFileType(), d.getRowCount(),
                d.getColumnCount(), d.getTableName(), columnsJson,
                d.getCreatedAt().toString(), d.getUpdatedAt().toString());
    }

    public List<Dataset> listDatasets() {
        return jdbc.query("SELECT * FROM datasets ORDER BY updated_at DESC", (rs, i) -> mapDataset(rs));
    }

    public Dataset getDataset(String id) {
        List<Dataset> list = jdbc.query("SELECT * FROM datasets WHERE id = ?", (rs, i) -> mapDataset(rs), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void deleteDataset(String id) {
        jdbc.update("DELETE FROM datasets WHERE id = ?", id);
    }

    private Dataset mapDataset(ResultSet rs) throws SQLException {
        Dataset d = new Dataset();
        d.setId(rs.getString("id"));
        d.setName(rs.getString("name"));
        d.setFileName(rs.getString("file_name"));
        d.setFileType(rs.getString("file_type"));
        d.setRowCount(rs.getInt("row_count"));
        d.setColumnCount(rs.getInt("column_count"));
        d.setTableName(rs.getString("table_name"));
        d.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
        d.setUpdatedAt(LocalDateTime.parse(rs.getString("updated_at")));
        try {
            String json = rs.getString("columns_json");
            if (json != null) {
                d.setColumns(Arrays.asList(mapper.readValue(json, com.axiom.model.ColumnInfo[].class)));
            }
        } catch (Exception ignored) {}
        return d;
    }

    // ---- sessions ----

    @org.springframework.transaction.annotation.Transactional
    public void saveSession(ChatSession s) {
        jdbc.update("DELETE FROM sessions WHERE id = ?", s.getId());
        jdbc.update("""
            INSERT INTO sessions (id, title, dataset_id, model, created_at, updated_at)
            VALUES (?,?,?,?,?,?)
            """, s.getId(), s.getTitle(), s.getDatasetId(), s.getModel(),
                s.getCreatedAt().toString(), s.getUpdatedAt().toString());
    }

    public List<ChatSession> listSessions() {
        List<ChatSession> sessions = jdbc.query("SELECT * FROM sessions ORDER BY updated_at DESC",
                (rs, i) -> {
                    ChatSession s = new ChatSession();
                    s.setId(rs.getString("id"));
                    s.setTitle(rs.getString("title"));
                    s.setDatasetId(rs.getString("dataset_id"));
                    s.setModel(rs.getString("model"));
                    s.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
                    s.setUpdatedAt(LocalDateTime.parse(rs.getString("updated_at")));
                    return s;
                });
        // 列表页不再加载消息：此前每个会话都拉全部消息含完整 payload（N+1），
        // 会话一多列表接口必然超时；消息详情由 GET /api/sessions/{id} 按需加载。
        // / The list endpoint must not load messages: per-session full payload loading
        // (N+1) makes the sidebar O(session × messages). Details come from the detail API.
        return sessions;
    }

    public ChatSession getSession(String id) {
        List<ChatSession> list = jdbc.query("SELECT * FROM sessions WHERE id = ?", (rs, i) -> {
            ChatSession s = new ChatSession();
            s.setId(rs.getString("id"));
            s.setTitle(rs.getString("title"));
            s.setDatasetId(rs.getString("dataset_id"));
            s.setModel(rs.getString("model"));
            s.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
            s.setUpdatedAt(LocalDateTime.parse(rs.getString("updated_at")));
            return s;
        }, id);
        if (list.isEmpty()) return null;
        ChatSession s = list.get(0);
        s.setMessages(listMessages(id));
        return s;
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteSession(String id) {
        jdbc.update("DELETE FROM messages WHERE session_id = ?", id);
        jdbc.update("DELETE FROM sessions WHERE id = ?", id);
    }

    public void updateSessionTitle(String id, String title) {
        jdbc.update("UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?", title, now(), id);
    }

    // ---- messages ----

    @org.springframework.transaction.annotation.Transactional
    public void saveMessage(String sessionId, ChatMessage m, String payloadJson) {
        jdbc.update("DELETE FROM messages WHERE id = ?", m.getId());
        jdbc.update("""
            INSERT INTO messages (id, session_id, role, content, model, payload_json, created_at)
            VALUES (?,?,?,?,?,?,?)
            """, m.getId(), sessionId, m.getRole(), m.getContent(), m.getModel(),
                payloadJson, (m.getCreatedAt() == null ? now() : m.getCreatedAt().toString()));
        jdbc.update("UPDATE sessions SET updated_at = ? WHERE id = ?", now(), sessionId);
    }

    public List<ChatMessage> listMessages(String sessionId) {
        return jdbc.query("SELECT * FROM messages WHERE session_id = ? ORDER BY created_at ASC",
                (rs, i) -> {
                    ChatMessage m = new ChatMessage();
                    m.setId(rs.getString("id"));
                    m.setRole(rs.getString("role"));
                    m.setContent(rs.getString("content"));
                    m.setModel(rs.getString("model"));
                    m.setCreatedAt(LocalDateTime.parse(rs.getString("created_at")));
                    String payload = rs.getString("payload_json");
                    if (payload != null) {
                        try {
                            var node = mapper.readTree(payload);
                            if (node.has("plan")) {
                                m.setPlan(mapper.treeToValue(node.get("plan"), com.axiom.model.AnalysisPlan.class));
                            }
                            if (node.has("result")) {
                                m.setResult(mapper.treeToValue(node.get("result"), com.axiom.model.AnalysisResult.class));
                            }
                        } catch (Exception ignored) {}
                    }
                    return m;
                }, sessionId);
    }
}
