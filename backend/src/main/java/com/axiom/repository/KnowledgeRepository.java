package com.axiom.repository;

import com.axiom.model.KnowledgeEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
                version INTEGER DEFAULT 1,
                deleted BOOLEAN DEFAULT FALSE,
                created_at VARCHAR(64),
                updated_at VARCHAR(64)
            )
        """);
        // 旧库迁移：为历史表补列 / Migrate legacy tables: add missing columns.
        try { jdbc.execute("ALTER TABLE knowledge_entries ADD COLUMN version INTEGER DEFAULT 1"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE knowledge_entries ADD COLUMN deleted BOOLEAN DEFAULT FALSE"); } catch (Exception ignored) {}
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

    /**
     * 保存或更新条目 / Insert or update an entry.
     * DELETE+INSERT 非原子，必须用事务保证失败时回滚（元数据管理器已 @Primary）。
     * / DELETE+INSERT is not atomic on its own; wrap in a transaction so a failed INSERT
     * rolls back the DELETE (meta transaction manager is @Primary).
     */
    @Transactional
    public void save(KnowledgeEntry e) {
        // DELETE+INSERT 兼容 H2 与 SQLite，充当 upsert / DELETE+INSERT works as upsert on both H2 and SQLite.
        jdbc.update("DELETE FROM knowledge_entries WHERE id = ?", e.getId());
        jdbc.update("""
            INSERT INTO knowledge_entries (
                id, kind, title, content, formula, fields_json, tags_json,
                source_type, source_id, dataset_id, chunk_index, text_hash,
                status, version, deleted, created_at, updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            e.getId(), e.getKind(), e.getTitle(), e.getContent(), e.getFormula(),
            toJson(e.getFields()), toJson(e.getTags()), e.getSourceType(), e.getSourceId(),
            e.getDatasetId(), e.getChunkIndex(), e.getTextHash(), e.getStatus(),
            e.getVersion(), e.isDeleted(), str(e.getCreatedAt()), str(e.getUpdatedAt()));
    }

    /** 按 ID 查询 / Find by ID. */
    public KnowledgeEntry findById(String id) {
        List<KnowledgeEntry> list = jdbc.query(
                "SELECT * FROM knowledge_entries WHERE id = ? AND (deleted IS NULL OR deleted = FALSE)",
                this::mapRow, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 ID 查询（含软删除，恢复用）/ Find by ID including soft-deleted (for restore). */
    public KnowledgeEntry findByIdIncludingDeleted(String id) {
        List<KnowledgeEntry> list = jdbc.query(
                "SELECT * FROM knowledge_entries WHERE id = ?", this::mapRow, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 列出全部条目（排除软删除）/ List all entries excluding soft-deleted. */
    public List<KnowledgeEntry> listAll() {
        return jdbc.query(
                "SELECT * FROM knowledge_entries WHERE deleted IS NULL OR deleted = FALSE ORDER BY updated_at DESC",
                this::mapRow);
    }

    /** 按类型过滤 / Filter by kind. */
    public List<KnowledgeEntry> listByKind(String kind) {
        return jdbc.query(
                "SELECT * FROM knowledge_entries WHERE kind = ? AND (deleted IS NULL OR deleted = FALSE) ORDER BY updated_at DESC",
                this::mapRow, kind);
    }

    /**
     * 列出全部已定义公式的条目（标题 → 公式，供确定性计算编译）/ List entries that
     * define a formula (title → expression) for deterministic metric computation.
     *
     * <p>只取轻量列，避免为公式编译拉取全量 content。/ Only lightweight columns are
     * selected to avoid pulling full content for formula compilation.
     *
     * @return title → formula 文本 / title-to-formula mapping.
     */
    public java.util.Map<String, String> listFormulas() {
        return jdbc.query(
                "SELECT title, formula FROM knowledge_entries "
                        + "WHERE formula IS NOT NULL AND formula <> '' AND (deleted IS NULL OR deleted = FALSE)",
                rs -> {
                    java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
                    while (rs.next()) map.put(rs.getString("title"), rs.getString("formula"));
                    return map;
                });
    }

    /** 检查内容哈希是否存在（仅未删除条目，幂等去重）/ Check whether a content hash exists among live entries. */
    public boolean existsByHash(String textHash) {
        if (textHash == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_entries WHERE text_hash = ? AND (deleted IS NULL OR deleted = FALSE)",
                Integer.class, textHash);
        return count != null && count > 0;
    }

    /** 按哈希查找未删除条目（幂等去重返回已有条目）/ Find a live entry by content hash. */
    public KnowledgeEntry findByHash(String textHash) {
        if (textHash == null) return null;
        List<KnowledgeEntry> hits = jdbc.query(
                "SELECT * FROM knowledge_entries WHERE text_hash = ? AND (deleted IS NULL OR deleted = FALSE) LIMIT 1",
                this::mapRow, textHash);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * 软删除条目 / Soft-delete an entry.
     * 数据保留但查询不可见，可恢复 / Data retained but hidden from queries, restorable.
     */
    public void delete(String id) {
        jdbc.update("UPDATE knowledge_entries SET deleted = TRUE, version = version + 1, updated_at = ? WHERE id = ?",
                java.time.LocalDateTime.now().toString(), id);
    }

    /** 恢复软删除的条目 / Restore a soft-deleted entry. */
    public void restore(String id) {
        jdbc.update("UPDATE knowledge_entries SET deleted = FALSE, version = version + 1, updated_at = ? WHERE id = ?",
                java.time.LocalDateTime.now().toString(), id);
    }

    /**
     * 按来源级联软删除，返回被删除的 ID / Cascade soft-delete by source, returning IDs.
     *
     * <p>采用「先记录已删集合 → UPDATE → 差集」而非先查后删：旧写法在 SELECT 与 UPDATE
     * 之间并发插入的条目会被本次 UPDATE 软删却不在返回的 ids 里，图谱子图清理因此漏删。
     * Instead of SELECT-then-UPDATE (a row inserted in between would be soft-deleted by
     * this call yet missing from the returned ids, so graph cleanup skips it), we snapshot
     * the already-deleted set first and diff after the UPDATE.
     */
    @Transactional
    public List<String> deleteBySource(String sourceId) {
        // 删除前已处于软删态的条目：来自历史删除调用，不属于本次结果。
        // / Entries already soft-deleted before this call belong to earlier deletes.
        java.util.Set<String> previouslyDeleted = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT id FROM knowledge_entries WHERE source_id = ? AND deleted = TRUE",
                String.class, sourceId));
        // 单条 UPDATE 替代逐条循环软删，避免 N+1 写放大；事务保证批量软删原子。
        // / One UPDATE replaces the per-row loop (avoids N+1 writes); transactional for atomicity.
        jdbc.update("UPDATE knowledge_entries SET deleted = TRUE, version = version + 1, updated_at = ? "
                        + "WHERE source_id = ? AND (deleted IS NULL OR deleted = FALSE)",
                java.time.LocalDateTime.now().toString(), sourceId);
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM knowledge_entries WHERE source_id = ? AND deleted = TRUE",
                String.class, sourceId);
        ids.removeIf(previouslyDeleted::contains);
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
    @Transactional
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
    /**
     * 按倒排索引检索（可按数据集过滤）/ Search via inverted index (optionally scoped to a dataset).
     *
     * <p>必须与向量检索保持同一数据集边界：全局条目（dataset_id 为空）与当前数据集条目均可命中，
     * 其他数据集的条目一律排除，防止跨业务域的口径串味。
     * / Must mirror the vector-search dataset boundary: global entries and the current dataset's
     * entries are eligible; entries from other datasets are excluded to avoid cross-domain mixing.
     *
     * @param terms     分词后的关键词 / Tokenized terms.
     * @param datasetId 当前数据集 ID，可为 null（仅全局）/ Current dataset id, nullable (global only).
     * @param limit     返回上限 / Result limit.
     * @return entry_id + 命中数 / entry_id with hit counts.
     */
    public List<java.util.Map<String, Object>> searchByTerms(List<String> terms, String datasetId, int limit) {
        if (terms == null || terms.isEmpty()) return List.of();
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
        }
        String sql = "SELECT t.entry_id, COUNT(*) AS hits FROM knowledge_terms t "
                + "JOIN knowledge_entries e ON e.id = t.entry_id "
                + "WHERE t.term IN (" + in + ") "
                + "AND (e.deleted IS NULL OR e.deleted = FALSE) "
                + "AND (e.dataset_id IS NULL OR e.dataset_id = ?) "
                + "GROUP BY t.entry_id ORDER BY hits DESC LIMIT " + Math.max(1, Math.min(limit, 100));
        Object[] args = new Object[terms.size() + 1];
        for (int i = 0; i < terms.size(); i++) args[i] = terms.get(i);
        args[terms.size()] = datasetId;
        return jdbc.queryForList(sql, args);
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
        try { e.setVersion(rs.getInt("version")); } catch (Exception ignored) { e.setVersion(1); }
        try { e.setDeleted(rs.getBoolean("deleted")); } catch (Exception ignored) { e.setDeleted(false); }
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
