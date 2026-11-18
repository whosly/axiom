package com.dataagent.repository;

import com.dataagent.model.KnowledgeEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 知识库元数据存储 / Knowledge base metadata repository.
 *
 * <p>使用 H2/SQLite（元数据型 OLTP 库）存储知识条目的结构化字段、来源状态和标签，
 * 向量部分由 Zvec 管理。/ Uses H2/SQLite (OLTP metadata store) to persist structured
 * fields, source status and tags of knowledge entries; vectors are managed by Zvec.
 *
 * <p>所有 SQL 使用 H2 与 SQLite 都兼容的 DELETE+INSERT 模式，不使用 H2 专有的 MERGE。
 * / All SQL uses DELETE+INSERT compatible with both H2 and SQLite, avoiding H2-specific MERGE.
 */
@Slf4j
@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeRepository(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 初始化知识库表结构 / Initialize knowledge base tables. */
    @PostConstruct
    public void init() {
        // 知识条目表：每条记录对应一个 KnowledgeEntry / One row per KnowledgeEntry.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_entries (
                id VARCHAR(64) PRIMARY KEY,
                kind VARCHAR(32) NOT NULL,
                title VARCHAR(255),
                content TEXT,
                formula TEXT,
                fields_json TEXT,
                tags_json TEXT,
                source_type VARCHAR(32),
                source_id VARCHAR(64),
                dataset_id VARCHAR(64),
                chunk_index INTEGER DEFAULT 0,
                text_hash VARCHAR(128),
                status VARCHAR(16) DEFAULT 'ready',
                created_at VARCHAR(64),
                updated_at VARCHAR(64)
            )
        """);
        // 倒排索引表：用于降级检索，避免全表 contains 扫描 / Inverted index for fallback search.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_terms (
                term VARCHAR(128) NOT NULL,
                entry_id VARCHAR(64) NOT NULL
            )
        """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kt_term ON knowledge_terms(term)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kt_entry ON knowledge_terms(entry_id)");

        // 常用过滤条件索引：按类型、来源、数据集 / Indexes for frequent filters: kind, source, dataset.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ke_kind ON knowledge_entries(kind)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ke_source ON knowledge_entries(source_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ke_dataset ON knowledge_entries(dataset_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ke_hash ON knowledge_entries(text_hash)");
        log.info("[Knowledge] metadata schema ready");
    }

    /** 保存或更新条目 / Insert or update an entry. */
    public void save(KnowledgeEntry e) {
        // DELETE+INSERT 兼容 H2 与 SQLite，充当 upsert / DELETE+INSERT works as upsert on both H2 and SQLite.
        jdbc.update("DELETE FROM knowledge_entries WHERE id = ?", e.getId());
        jdbc.update("""
            INSERT INTO knowledge_entries (
                id, kind, title, content, formula, fields_json, tags_json,
                source_type, source_id, dataset_id, chunk_index, text_hash,
                status, created_at, updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            e.getId(), e.getKind(), e.getTitle(), e.getContent(), e.getFormula(),
            toJson(e.getFields()), toJson(e.getTags()), e.getSourceType(), e.getSourceId(),
            e.getDatasetId(), e.getChunkIndex(), e.getTextHash(), e.getStatus(),
            str(e.getCreatedAt()), str(e.getUpdatedAt()));
    }

    /** 按 ID 查询 / Find by ID. */
    public KnowledgeEntry findById(String id) {
        List<KnowledgeEntry> list = jdbc.query(
                "SELECT * FROM knowledge_entries WHERE id = ?", this::mapRow, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 列出全部条目 / List all entries. */
    public List<KnowledgeEntry> listAll() {
        return jdbc.query("SELECT * FROM knowledge_entries ORDER BY updated_at DESC", this::mapRow);
    }

    /** 按类型过滤 / Filter by kind. */
    public List<KnowledgeEntry> listByKind(String kind) {
        return jdbc.query(
                "SELECT * FROM knowledge_entries WHERE kind = ? ORDER BY updated_at DESC",
                this::mapRow, kind);
    }

    /** 按来源 ID 查询（级联删除用）/ Find by source ID (for cascade deletion). */
    public List<KnowledgeEntry> listBySource(String sourceId) {
        return jdbc.query(
                "SELECT * FROM knowledge_entries WHERE source_id = ?", this::mapRow, sourceId);
    }

    /** 检查内容哈希是否存在（幂等去重）/ Check whether a content hash exists (idempotency). */
    public boolean existsByHash(String textHash) {
        if (textHash == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entries WHERE text_hash = ?", Integer.class, textHash);
        return count != null && count > 0;
    }

    /** 删除条目 / Delete an entry. */
    public void delete(String id) {
        jdbc.update("DELETE FROM knowledge_entries WHERE id = ?", id);
    }

    /** 按来源级联删除，返回被删除的 ID / Cascade delete by source, returning deleted IDs. */
    public List<String> deleteBySource(String sourceId) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM knowledge_entries WHERE source_id = ?", String.class, sourceId);
        jdbc.update("DELETE FROM knowledge_entries WHERE source_id = ?", sourceId);
        return ids;
    }

    /** 统计各类型数量 / Count entries grouped by kind. */
    public List<java.util.Map<String, Object>> stats() {
        return jdbc.queryForList(
                "SELECT kind, COUNT(*) AS count FROM knowledge_entries GROUP BY kind ORDER BY count DESC");
    }

    /**
     * 查询向量未就绪的条目（outbox 模式）/ Find entries whose vector is not ready (outbox pattern).
     *
     * <p>这些是元数据已写入但向量写入失败的条目，供后台重试重建。
     * / These are entries with metadata persisted but vector write failed; for background retry.
     */
    public List<KnowledgeEntry> listPendingVector() {
        return jdbc.query(
                "SELECT * FROM knowledge_entries WHERE status IN ('pending','vector_failed') ORDER BY updated_at ASC",
                this::mapRow);
    }

    /** 更新单条状态 / Update the status of one entry. */
    public void updateStatus(String id, String status) {
        jdbc.update("UPDATE knowledge_entries SET status = ?, updated_at = ? WHERE id = ?",
                status, java.time.LocalDateTime.now().toString(), id);
    }

    /** 统计各状态数量 / Count entries grouped by status. */
    public List<java.util.Map<String, Object>> statusStats() {
        return jdbc.queryForList(
                "SELECT status, COUNT(*) AS count FROM knowledge_entries GROUP BY status ORDER BY count DESC");
    }

    /**
     * 倒排索引：批量写入某个条目的分词 / Inverted index: store tokens of an entry.
     *
     * <p>先删后插保证幂等；term 上有索引，检索走索引而非全表 contains 扫描。
     * / Delete-then-insert for idempotency; term is indexed so lookup avoids full-table contains scans.
     *
     * @param entryId 条目 ID / Entry ID.
     * @param terms   分词列表 / Token list.
     */
    public void indexTerms(String entryId, List<String> terms) {
        jdbc.update("DELETE FROM knowledge_terms WHERE entry_id = ?", entryId);
        if (terms == null || terms.isEmpty()) return;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            jdbc.update("INSERT INTO knowledge_terms (term, entry_id) VALUES (?, ?)", term, entryId);
        }
    }

    /**
     * 倒排索引检索：按分词命中数排序 / Inverted-index search: rank by term hits.
     *
     * @param terms 查询分词 / Query tokens.
     * @param limit 返回条数 / Result limit.
     * @return 命中的条目 ID 与命中数 / Hit entry IDs with hit counts.
     */
    public List<java.util.Map<String, Object>> searchByTerms(List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) return List.of();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
        }
        String sql = "SELECT entry_id, COUNT(*) AS hits FROM knowledge_terms WHERE term IN (" + in
                + ") GROUP BY entry_id ORDER BY hits DESC LIMIT " + Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList(sql, terms.toArray());
    }

    // ---- helpers ----

    /** 将 ResultSet 映射为 KnowledgeEntry / Map a ResultSet row to KnowledgeEntry. */
    private KnowledgeEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeEntry e = new KnowledgeEntry();
        e.setId(rs.getString("id"));
        e.setKind(rs.getString("kind"));
        e.setTitle(rs.getString("title"));
        e.setContent(rs.getString("content"));
        e.setFormula(rs.getString("formula"));
        e.setFields(fromJson(rs.getString("fields_json"), String.class));
        e.setTags(fromJson(rs.getString("tags_json"), String.class));
        e.setSourceType(rs.getString("source_type"));
        e.setSourceId(rs.getString("source_id"));
        e.setDatasetId(rs.getString("dataset_id"));
        e.setChunkIndex(rs.getInt("chunk_index"));
        e.setTextHash(rs.getString("text_hash"));
        e.setStatus(rs.getString("status"));
        e.setCreatedAt(parse(rs.getString("created_at")));
        e.setUpdatedAt(parse(rs.getString("updated_at")));
        return e;
    }

    /** 序列化列表为 JSON，空列表返回 null / Serialize list to JSON; null for empty list. */
    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try { return mapper.writeValueAsString(list); }
        catch (Exception e) { return null; }
    }

    /** 反序列化 JSON 字符串数组 / Deserialize a JSON string array. */
    private List<String> fromJson(String json, Class<String> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            String[] arr = mapper.readValue(json, String[].class);
            return Arrays.asList(arr);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s); }
        catch (Exception e) { return null; }
    }

    private static String str(LocalDateTime t) {
        return t == null ? LocalDateTime.now().toString() : t.toString();
    }
}
