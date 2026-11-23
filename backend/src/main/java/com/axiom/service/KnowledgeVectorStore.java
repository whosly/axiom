package com.axiom.service;

import com.axiom.config.AppProperties;
import com.axiom.embedding.EmbeddingProviderFactory;
import com.axiom.model.KnowledgeEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.zvec.binding.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 知识库向量存储 / Knowledge-base vector store.
 *
 * <p>管理独立的 knowledge_base collection，提供两类能力：
 * Manages a dedicated knowledge_base collection and provides two capabilities:
 * <ol>
 *   <li>知识条目的向量写入与删除 / Vector upsert and deletion of knowledge entries</li>
 *   <li>混合检索：向量召回 + jieba 全文召回，经 Zvec 原生 RRF 融合重排
 *       / Hybrid retrieval: vector recall + jieba full-text recall, fused by Zvec native RRF</li>
 * </ol>
 *
 * <p>设计取舍：使用 Zvec 内置的 MultiQuery.setRerankRrf() 而非自行实现 RRF，
 * 减少 Java 层与 native 层之间的数据往返。
 * Design trade-off: use Zvec's built-in MultiQuery.setRerankRrf() instead of a manual
 * Java-side RRF, reducing data round-trips between Java and the native layer.
 */
@Slf4j
@Service
public class KnowledgeVectorStore {

    /** Zvec Collection 对象，对应 knowledge_base / Zvec collection for knowledge_base. */
    private Collection collection;

    /** collection 是否就绪 / Whether the collection is ready. */
    private boolean ready = false;

    /** 新建 collection（首次/迁移）需要全量重建向量 / Newly created collection needs a full reindex. */
    private volatile boolean needsFullReindex = false;

    private final AppProperties props;
    private final EmbeddingProviderFactory embeddingFactory;
    private final ZvecLifecycle lifecycle;

    public KnowledgeVectorStore(AppProperties props, EmbeddingProviderFactory embeddingFactory, ZvecLifecycle lifecycle) {
        this.props = props;
        this.embeddingFactory = embeddingFactory;
        this.lifecycle = lifecycle;
    }

    /**
     * 初始化 knowledge_base collection / Initialize the knowledge_base collection.
     *
     * <p>若目录已存在则 open（重启场景），否则 createAndOpen（首次）。
     * If the directory exists, open it (restart case); otherwise createAndOpen (first run).
     */
    @PostConstruct
    public void init() {
        if (!props.getZvec().isEnabled() || !lifecycle.isInitialized()) {
            log.info("[KnowledgeVector] disabled");
            return;
        }
        try {
            Path dir = Path.of(props.getZvec().getKnowledgePath());
            CollectionSchema schema = buildSchema();

            if (Files.exists(dir)) {
                // 重启时目录已存在，只能用 open / On restart the dir exists, so open it.
                collection = Zvec.open(dir.toString(), null);
                log.info("[KnowledgeVector] opened existing collection at {}", dir.toAbsolutePath());
            } else {
                collection = Zvec.createAndOpen(dir.toString(), schema, null);
                // 新建 collection 没有历史向量，标记启动后全量重建 / New collection is empty; flag a full reindex.
                needsFullReindex = true;
                log.info("[KnowledgeVector] created collection at {} (full reindex will follow)",
                        dir.toAbsolutePath());
            }
            schema.close();
            ready = true;
        } catch (Throwable t) {
            // 向量检索失败不应阻断主流程，降级为元数据检索 / Vector failure must not block main flow.
            log.warn("[KnowledgeVector] init failed, vector search degraded: {}", t.getMessage());
            ready = false;
        }
    }

    /** 构建 knowledge_base 的索引结构 / Build the schema of the knowledge_base collection. */
    private CollectionSchema buildSchema() {
        CollectionSchema schema = new CollectionSchema(props.getZvec().getKnowledgeCollection());

        // 稠密向量字段，HNSW + COSINE 索引 / Dense vector field with HNSW + COSINE index.
        FieldSchema vectorField = new FieldSchema("embedding", DataType.VECTOR_FP32, false,
                props.getZvec().getDimension());
        try (IndexParams hnsw = IndexParams.createHNSW(MetricType.COSINE, 32, 200)) {
            vectorField.setIndexParams(hnsw);
        }
        schema.addField(vectorField);

        // 标量字段：kind/title/content/sourceId/datasetId 均为可过滤或返回字段
        // Scalar fields: kind/title/content/sourceId/datasetId are filterable or returned.
        schema.addField(new FieldSchema("kind", DataType.STRING, true, 0));
        schema.addField(new FieldSchema("title", DataType.STRING, true, 0));

        // content 必须建 FTS 索引：hybridSearch 的 jieba 全文召回子查询要求该字段
        // 具备 FTS 索引，否则查询报 "fts requires an FTS-indexed field"。
        // tokenizer 用 Zvec 内置的 "jieba"（中文分词），filters 传空数组。
        // content needs an FTS index: the jieba FTS sub-query requires it, otherwise
        // "fts requires an FTS-indexed field" is thrown. Use Zvec's built-in "jieba".
        FieldSchema contentField = new FieldSchema("content", DataType.STRING, true, 0);
        try (IndexParams fts = IndexParams.createFTS("jieba", new String[0], null)) {
            contentField.setIndexParams(fts);
        }
        schema.addField(contentField);

        schema.addField(new FieldSchema("sourceId", DataType.STRING, true, 0));
        schema.addField(new FieldSchema("datasetId", DataType.STRING, true, 0));
        schema.addField(new FieldSchema("tags", DataType.STRING, true, 0));

        // chunkIndex 为整数字段，支持按分块顺序过滤 / chunkIndex is an int field for chunk-order filtering.
        schema.addField(new FieldSchema("chunkIndex", DataType.INT32, true, 0));
        return schema;
    }

    /**
     * 写入知识条目的向量 / Upsert the vector of a knowledge entry.
     *
     * @param entry 知识条目（其 content 会被向量化）/ Knowledge entry (its content is embedded).
     */
    public void upsert(KnowledgeEntry entry) {
        upsertWithResult(entry);
    }

    /**
     * 写入向量并返回结果，供上层维护双写状态 / Upsert vector and return result for dual-write status.
     *
     * @param entry 知识条目 / Knowledge entry.
     * @return 向量是否成功写入 / Whether the vector write succeeded.
     */
    public boolean upsertWithResult(KnowledgeEntry entry) {
        if (!ready || entry.getContent() == null || entry.getContent().isBlank()) return false;
        // Doc 使用 native 内存；用局部变量持有，finally 无条件释放，避免异常路径泄漏。
        // Docs use native memory; hold in a local and free in finally so exception paths cannot leak.
        List<Doc> docs = null;
        try {
            float[] vector = alignDimension(embeddingFactory.current().embed(entry.getContent()));

            Doc doc = new Doc();
            doc.setPK(entry.getId());
            doc.addStringField("kind", nullToEmpty(entry.getKind()));
            doc.addStringField("title", nullToEmpty(entry.getTitle()));
            doc.addStringField("content", entry.getContent());
            doc.addStringField("sourceId", nullToEmpty(entry.getSourceId()));
            doc.addStringField("datasetId", nullToEmpty(entry.getDatasetId()));
            doc.addStringField("tags", String.join(",", entry.getTags() == null ? List.of() : entry.getTags()));
            doc.addInt32Field("chunkIndex", entry.getChunkIndex());
            doc.addVectorFP32Field("embedding", vector);

            docs = new ArrayList<>();
            docs.add(doc);
            collection.upsert(docs);
            collection.flush();
            return true;
        } catch (Exception e) {
            log.warn("[KnowledgeVector] upsert failed for {}: {}", entry.getId(), e.getMessage());
            return false;
        } finally {
            if (docs != null) Doc.freeDocs(docs);
        }
    }

    /**
     * 批量写入知识条目 / Batch upsert knowledge entries.
     *
     * @param entries 条目列表 / List of entries.
     */
    public void upsertBatch(List<KnowledgeEntry> entries) {
        if (!ready || entries == null || entries.isEmpty()) return;
        for (KnowledgeEntry entry : entries) upsert(entry);
    }

    /**
     * 混合检索：向量召回 + 全文召回，Zvec 内部 RRF 融合。
     * Hybrid retrieval: vector recall + full-text recall, fused by Zvec's internal RRF.
     *
     * <p>关键逻辑 / Key logic:
     * <ol>
     *   <li>对每个查询文本生成 embedding，构造一个 vector SubQuery / Embed each query and build a vector SubQuery</li>
     *   <li>对同一文本构造 FTS SubQuery，捕获精确关键词匹配 / Build an FTS SubQuery for exact keyword matches</li>
     *   <li>MultiQuery.setRerankRrf(1) 启用倒数排名融合 / Enable reciprocal rank fusion</li>
     *   <li>filter 表达式限制 kind/datasetId / Filter expression restricts kind/datasetId</li>
     * </ol>
     *
     * @param query      用户查询文本 / User query text.
     * @param kindFilter 类型过滤（可空）/ Kind filter (nullable).
     * @param datasetId  数据集过滤（可空，空表示不过滤）/ Dataset filter (nullable, empty means no filter).
     * @param topK       返回条数 / Number of results.
     * @return 命中列表（按融合分数排序）/ Hit list sorted by fused score.
     */
    public List<Hit> hybridSearch(String query, String kindFilter, String datasetId, int topK) {
        if (!ready || query == null || query.isBlank()) return Collections.emptyList();
        try {
            float[] vector = alignDimension(embeddingFactory.current().embed(query));
            String filter = buildFilter(kindFilter, datasetId);

            // 用 try-with-resources 确保 native 对象释放 / try-with-resources ensures native objects are freed.
            try (MultiQuery multi = new MultiQuery();
                 SubQuery vectorSub = new SubQuery();
                 SubQuery ftsSub = new SubQuery();
                 FtsPayload ftsPayload = new FtsPayload()) {

                // 子查询 1：稠密向量召回 / Sub-query 1: dense vector recall.
                vectorSub.setFieldName("embedding");
                vectorSub.setQueryVector(vector);
                vectorSub.setNumCandidates(Math.max(topK * 4, 50));

                // 子查询 2：jieba 全文召回，捕获指标名等精确词 / Sub-query 2: jieba FTS for exact terms like metric names.
                ftsPayload.setQueryString(query);
                ftsSub.setFieldName("content");
                ftsSub.setFts(ftsPayload);

                multi.addSubQuery(vectorSub);
                multi.addSubQuery(ftsSub);
                multi.setTopk(topK);
                // RRF 比加权分数更稳定：只依赖排名，不依赖不同召回分支的分数尺度。
                // RRF is more robust than score weighting: it relies only on ranks, not on incomparable score scales.
                multi.setRerankRrf(1);
                if (filter != null) multi.setFilter(filter);

                List<Doc> results = collection.query(multi);
                try {
                    List<Hit> hits = new ArrayList<>();
                    for (Doc d : results) {
                        hits.add(new Hit(
                                d.getPK(),
                                d.getStringField("kind"),
                                d.getStringField("title"),
                                d.getStringField("content"),
                                d.getStringField("sourceId"),
                                d.getScore()));
                    }
                    return hits;
                } finally {
                    Doc.freeDocs(results);
                }
            }
        } catch (Exception e) {
            log.warn("[KnowledgeVector] hybrid search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构造 Zvec filter 表达式 / Build a Zvec filter expression.
     *
     * <p>注意：空字符串表示全局知识，不过滤数据集字段。
     * Note: an empty datasetId means global knowledge; do not filter on the dataset field.
     */
    private String buildFilter(String kindFilter, String datasetId) {
        List<String> conditions = new ArrayList<>();
        if (kindFilter != null && !kindFilter.isBlank()) {
            conditions.add("kind = '" + escape(kindFilter) + "'");
        }
        if (datasetId != null && !datasetId.isBlank()) {
            // 命中全局知识（datasetId 为空）或本数据集知识 / Match global knowledge (empty datasetId) or this dataset.
            conditions.add("(datasetId = '' OR datasetId = '" + escape(datasetId) + "')");
        }
        return conditions.isEmpty() ? null : String.join(" AND ", conditions);
    }

    /** 删除指定来源的全部向量 / Delete all vectors of a given source. */
    public void deleteBySource(String sourceId) {
        if (!ready) return;
        try {
            collection.deleteByFilter("sourceId = '" + escape(sourceId) + "'");
            collection.flush();
        } catch (Exception e) {
            log.warn("[KnowledgeVector] delete by source failed: {}", e.getMessage());
        }
    }

    /** 删除单条向量 / Delete a single vector by ID. */
    public void delete(String id) {
        if (!ready) return;
        try {
            collection.delete(List.of(id));
            collection.flush();
        } catch (Exception e) {
            log.warn("[KnowledgeVector] delete failed for {}: {}", id, e.getMessage());
        }
    }

    /**
     * 检查给定 ID 的向量是否已写入 / Check whether a vector exists for the given ID.
     *
     * <p>用于知识详情页展示「元数据已入库但向量缺失」的不一致状态
     * (e.g. embedding API not configured).
     *
     * @param id 知识条目 ID / Knowledge entry ID.
     * @return 向量状态信息 / Vector status info.
     */
    public VectorStatus vectorStatus(String id) {
        if (!ready) return new VectorStatus(false, 0, 0);
        try {
            // fetch 返回存在的文档；不存在则返回空列表 / fetch returns existing docs; empty if absent.
            List<Doc> docs = collection.fetch(List.of(id));
            try {
                if (docs.isEmpty()) return new VectorStatus(false, 0, 0);
                Doc d = docs.get(0);
                float[] vec = d.getVectorFP32Field("embedding");
                return new VectorStatus(true, vec == null ? 0 : vec.length,
                        d.hasField("content") && d.getStringField("content") != null
                                ? d.getStringField("content").length() : 0);
            } finally {
                Doc.freeDocs(docs);
            }
        } catch (Exception e) {
            log.debug("[KnowledgeVector] vector status failed for {}: {}", id, e.getMessage());
            return new VectorStatus(false, 0, 0);
        }
    }

    /** 向量状态 / Vector status value object. */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class VectorStatus {
        /** 向量是否存在 / Whether the vector exists. */
        private final boolean present;
        /** 向量维度 / Vector dimension. */
        private final int dimension;
        /** 存储的内容长度 / Stored content length. */
        private final int contentLength;
    }

    /** 关闭 collection / Close the collection. */
    @PreDestroy
    public void close() {
        if (collection != null) {
            try { collection.close(); } catch (Exception ignored) {}
        }
    }

    public boolean isReady() { return ready; }

    /** 是否需要启动后全量重建向量 / Whether a full reindex is needed at startup. */
    public boolean isNeedsFullReindex() { return needsFullReindex; }

    /**
     * 暴露 collection 供检视工具只读查看 / Expose the collection for read-only inspection.
     * 调用方只读，不应写入 / Callers must treat it as read-only.
     */
    public Collection rawCollection() { return collection; }

    // ---- helpers ----

    /** 对齐向量维度，不足补零，超出截断 / Align vector dimension: zero-pad or truncate. */
    private float[] alignDimension(float[] input) {
        int target = props.getZvec().getDimension();
        if (input.length == target) return input;
        float[] out = new float[target];
        System.arraycopy(input, 0, out, 0, Math.min(input.length, target));
        return out;
    }

    /** 转义 filter 字符串中的单引号 / Escape single quotes in filter strings. */
    private String escape(String s) { return s.replace("'", "''"); }

    private String nullToEmpty(String s) { return s == null ? "" : s; }

    /**
     * 检索命中结果 / Retrieval hit.
     *
     * <p>包含完整溯源信息（sourceId）以便前端展示引用来源。
     * Includes full traceability (sourceId) so the frontend can show citations.
     */
    @lombok.Data
    public static class Hit {
        private final String id;
        private final String kind;
        private final String title;
        private final String content;
        private final String sourceId;
        private final float score;

        public Hit(String id, String kind, String title, String content, String sourceId, float score) {
            this.id = id; this.kind = kind; this.title = title;
            this.content = content; this.sourceId = sourceId; this.score = score;
        }
    }
}
