package com.axiom.service;

import com.axiom.config.AppProperties;
import com.axiom.embedding.EmbeddingProviderFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.zvec.binding.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集语义向量服务 / Dataset-semantics vector service.
 *
 * <p>职责 / Responsibilities:
 * 1. 索引数据集字段语义，用于自然语言到字段映射 / Index column semantics for NL-to-field mapping
 * 2. 索引历史分析问答，用于 few-shot 检索 / Index past analysis Q&A for few-shot retrieval
 * 3. 索引分析结论，用于跨会话语义检索 / Index conclusions for cross-session search
 *
 * <p>本类只管理 dataset_semantics collection；知识库 collection 由 KnowledgeVectorStore 管理。
 * Zvec runtime 的初始化/关闭由 ZvecLifecycle 统一负责。
 * This class manages only the dataset_semantics collection; the knowledge collection is
 * managed by KnowledgeVectorStore. Zvec runtime lifecycle is owned by ZvecLifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorService {

    private final AppProperties props;
    private final EmbeddingProviderFactory embeddingFactory;
    private final ZvecLifecycle lifecycle;

    private Collection collection;
    private boolean ready = false;
    /**
     * Zvec native Collection 的调用串行锁 / Serializes all native Collection calls.
     *
     * <p>flush 与 query/upsert 并发可能读到写入中间态（刚写入却搜不到），
     * native 库的线程安全性未知，因此所有调用在本服务内串行化。
     * / Concurrent flush/query can observe intermediate states; the native thread-safety
     * is unspecified, so all calls are serialized behind this single lock.
     */
    private final Object zvecLock = new Object();

    @PostConstruct
    public void init() {
        if (!props.getZvec().isEnabled() || !lifecycle.isInitialized()) {
            log.info("[Zvec] dataset-semantics collection disabled");
            return;
        }
        try {
            Path dir = Path.of(props.getZvec().getPath());

            // Zvec.initialize() 已由 ZvecLifecycle 调用，此处不再重复 / Already initialized by ZvecLifecycle.
            // schema 本体也持 native 内存：open/create 失败的异常路径同样要释放，
            // 否则 init 失败降级时泄漏。/ The schema itself holds native memory and must be
            // released even when open/create throws on the degraded path.
            CollectionSchema schema = new CollectionSchema(props.getZvec().getCollection());
            try {
                // vectorField 也必须 close：FieldSchema 持有 native 资源，旁边的字段都用了
                // try-with-resources，这里漏掉属于笔误级泄漏。/ vectorField holds native memory
                // too; the surrounding fields all use try-with-resources, this one was a leak.
                try (FieldSchema vectorField = new FieldSchema("embedding", DataType.VECTOR_FP32, false,
                        props.getZvec().getDimension())) {
                    try (IndexParams hnsw = IndexParams.createHNSW(MetricType.COSINE, 32, 200)) {
                        vectorField.setIndexParams(hnsw);
                    }
                    schema.addField(vectorField);
                }

                try (FieldSchema textField = new FieldSchema("text", DataType.STRING, true, 0)) {
                    schema.addField(textField);
                }
                try (FieldSchema kindField = new FieldSchema("kind", DataType.STRING, true, 0)) {
                    schema.addField(kindField);
                }
                try (FieldSchema refField = new FieldSchema("refId", DataType.STRING, true, 0)) {
                    schema.addField(refField);
                }

                if (Files.exists(dir)) {
                    collection = Zvec.open(dir.toString(), null);
                    log.info("[Zvec] opened existing collection at {}", dir.toAbsolutePath());
                } else {
                    collection = Zvec.createAndOpen(dir.toString(), schema, null);
                    log.info("[Zvec] created collection at {}", dir.toAbsolutePath());
                }
                ready = true;
            } finally {
                try {
                    schema.close();
                } catch (Exception ce) {
                    log.warn("[Zvec] schema close failed: {}", ce.getMessage());
                }
            }
            log.info("[Zvec] initialized at {}", dir.toAbsolutePath());
        } catch (Throwable t) {
            log.warn("[Zvec] initialization failed, vector features will be degraded: {}", t.getMessage());
            ready = false;
        }
    }

    /**
     * 关闭本服务持有的 collection / Close the collection owned by this service.
     * <p>runtime 级关闭由 ZvecLifecycle 负责，但 dataset_semantics collection 是本服务
     * 独立打开的，必须自己释放，否则进程退出时 native 句柄泄漏、flush 无保证。
     * / Runtime-level shutdown stays with ZvecLifecycle, but the dataset_semantics
     * collection is opened here and must be released by us.
     */
    @PreDestroy
    public void shutdown() {
        if (collection != null) {
            try { collection.close(); } catch (Exception ignored) {}
        }
    }

    public boolean isReady() { return ready; }

    /**
     * 暴露 collection 供检视工具只读查看 / Expose the collection for read-only inspection.
     * 调用方只读，不应写入 / Callers must treat it as read-only.
     */
    public Collection rawCollection() { return collection; }

    /**
     * 在串行锁内执行 collection 检视操作 / Run a collection inspection under the serial lock.
     *
     * <p>Inspector 的 native 调用（stats/iterator/fetch）同样必须与写入/flush 串行化，
     * 否则与并发的 upsert/flush 相撞可能在 native 层崩溃。绕过本方法直接调
     * {@link #rawCollection()} 违反串行化纪律。
     * / Inspector's native calls (stats/iterator/fetch) must also be serialized with
     * writes/flushes — racing with concurrent upsert/flush can crash the native layer.
     */
    public <T> T inspect(java.util.function.Supplier<T> action) {
        synchronized (zvecLock) {
            return action.get();
        }
    }

    /**
     * 查询向量 LRU 缓存：同一问题文本在 field/history 等多次检索与跨阶段调用中
     * 复用同一次 embedding，避免一次分析对同一问题重复调 3-4 次 embedding API
     * （本地模式则是 4 次 ONNX 推理）。key 带厂商前缀，热切换模型后旧缓存自然失效。
     * 只缓存检索查询，不缓存 upsert 内容（内容唯一且体积大，缓存只会白白占内存）。
     * / Query-vector LRU cache: one analysis re-embeds the same question 3-4 times
     * across field/history/kb lookups. Keys are provider-prefixed so a hot provider
     * switch invalidates naturally. Search queries only — upsert contents are unique
     * and bulky, caching them would waste memory.
     */
    private final Map<String, float[]> queryVectorCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > 128;
                }
            });

    /** 带缓存的查询向量 / Cached query embedding. */
    private float[] embedCached(String text) {
        String key = embeddingFactory.current().providerName() + "::" + text;
        float[] cached = queryVectorCache.get(key);
        if (cached != null) return cached;
        // embed 在缓存锁外执行，慢请求不阻塞其他检索 / Embed outside the map lock so a
        // slow request does not stall other lookups.
        float[] vector = alignDimension(embeddingFactory.current().embed(text));
        queryVectorCache.put(key, vector);
        return vector;
    }

    /** 批量写入的语义条目 / Semantic item for batched writes. */
    public record SemanticItem(String id, String kind, String refId, String text) {
    }

    /**
     * 批量写入语义向量 / Batch upsert semantic items.
     *
     * <p>锁外逐条 embed + 构建 Doc，锁内一次 upsert + 一次 flush：字段语义索引、
     * 历史沉淀等 N 条写入只付一次刷盘代价，与 KnowledgeVectorStore.upsertBatch
     * 的设计一致。单条失败只跳过该条。
     * / Embed + build docs outside the lock, one upsert + one flush inside: N writes
     * pay one flush cost, matching KnowledgeVectorStore.upsertBatch. A failing item
     * is skipped without poisoning the rest.
     */
    public void upsertAll(List<SemanticItem> items) {
        if (!ready || items == null || items.isEmpty()) return;
        List<Doc> docs = new ArrayList<>(items.size());
        try {
            for (SemanticItem item : items) {
                if (item.text() == null || item.text().isBlank()) continue;
                Doc doc = new Doc();
                doc.setPK(item.id());
                doc.addStringField("text", item.text());
                doc.addStringField("kind", item.kind());
                doc.addStringField("refId", item.refId());
                doc.addVectorFP32Field("embedding", alignDimension(embeddingFactory.current().embed(item.text())));
                docs.add(doc);
            }
            if (docs.isEmpty()) return;
            synchronized (zvecLock) {
                collection.upsert(docs);
                collection.flush();
            }
        } catch (Exception e) {
            log.warn("[Zvec] batch upsert failed ({} items): {}", docs.size(), e.getMessage());
        } finally {
            if (!docs.isEmpty()) Doc.freeDocs(docs);
        }
    }

    /** Upsert a piece of semantic text into the vector store. */
    public void upsert(String id, String kind, String refId, String text) {
        if (!ready) return;
        // Doc 使用 native 内存，finally 无条件释放 / Docs use native memory; free in finally.
        List<Doc> docs = null;
        try {
            float[] vector = alignDimension(embeddingFactory.current().embed(text));

            Doc doc = new Doc();
            doc.setPK(id);
            doc.addStringField("text", text);
            doc.addStringField("kind", kind);
            doc.addStringField("refId", refId);
            doc.addVectorFP32Field("embedding", vector);

            docs = new ArrayList<>();
            docs.add(doc);
            synchronized (zvecLock) {
                collection.upsert(docs);
                collection.flush();
            }
        } catch (Exception e) {
            log.warn("[Zvec] upsert failed for {}: {}", id, e.getMessage());
        } finally {
            if (docs != null) Doc.freeDocs(docs);
        }
    }

    /**
     * 语义检索 / Semantic search with optional kind filter.
     *
     * <p>kind 过滤下推到向量查询（filter 表达式），与 hybridSearch 使用同一机制。
     * 不做本地过滤回退：filter 是 native 层的一等能力（姊妹实现长期稳定运行），
     * 若其失败属于集成缺陷，应当暴露（log.warn + 空结果）而非静默切换到
     * 语义不同的实现。/ The kind filter is pushed down via the same mechanism as
     * hybridSearch. No local-filter fallback: if the native filter fails that is an
     * integration bug and must surface (warn + empty) rather than silently switch to
     * a semantically different path.
     */
    public List<Hit> search(String query, String kindFilter, int topK) {
        if (!ready) return Collections.emptyList();
        try {
            float[] vector = embedCached(query);

            try (VectorQuery vq = new VectorQuery()) {
                vq.setTopK(topK);
                vq.setFieldName("embedding");
                vq.setQueryVector(vector);
                if (kindFilter != null && !kindFilter.isBlank()) {
                    // 与 KnowledgeVectorStore.buildFilter 同样的表达式 / Same expression form.
                    vq.setFilter("kind = '" + kindFilter.replace("'", "''") + "'");
                }
                List<Doc> results;
                synchronized (zvecLock) {
                    results = collection.query(vq);
                }
                try {
                    List<Hit> hits = new ArrayList<>();
                    for (Doc d : results) {
                        hits.add(new Hit(d.getPK(), d.getStringField("kind"),
                                d.getStringField("refId"), d.getStringField("text"), d.getScore()));
                    }
                    return hits;
                } finally {
                    Doc.freeDocs(results);
                }
            }
        } catch (Exception e) {
            log.warn("[Zvec] search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除某数据集的全部向量 / Remove all vectors associated with a dataset.
     *
     * <p>改用 deleteByFilter 按 refId 过滤删除：不依赖内存 trackedIds（重启即丢失，
     * 导致重启后的数据集向量永远无法清理）。/ Uses deleteByFilter instead of the in-memory
     * trackedIds map, which is lost on restart and would leave vectors undeletable.
     */
    public void deleteByRefId(String refId) {
        if (!ready) return;
        String escaped = refId == null ? "" : refId.replace("'", "''");
        try {
            synchronized (zvecLock) {
                collection.deleteByFilter("refId = '" + escaped + "'");
                collection.flush();
            }
        } catch (Exception e) {
            log.warn("[Zvec] delete failed for {}: {}", refId, e.getMessage());
        }
    }

    private float[] alignDimension(float[] input) {
        int target = props.getZvec().getDimension();
        if (input.length == target) return input;
        float[] out = new float[target];
        System.arraycopy(input, 0, out, 0, Math.min(input.length, target));
        return out;
    }

    @Data
    public static class Hit {
        private final String id;
        private final String kind;
        private final String refId;
        private final String text;
        private final float score;

        public Hit(String id, String kind, String refId, String text, float score) {
            this.id = id; this.kind = kind; this.refId = refId; this.text = text; this.score = score;
        }
    }
}
