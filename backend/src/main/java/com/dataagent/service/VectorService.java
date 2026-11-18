package com.dataagent.service;

import com.dataagent.config.AppProperties;
import com.dataagent.embedding.EmbeddingProviderFactory;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.zvec.binding.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
    private final Map<String, List<String>> trackedIds = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (!props.getZvec().isEnabled() || !lifecycle.isInitialized()) {
            log.info("[Zvec] dataset-semantics collection disabled");
            return;
        }
        try {
            Path dir = Path.of(props.getZvec().getPath());

            // Zvec.initialize() 已由 ZvecLifecycle 调用，此处不再重复 / Already initialized by ZvecLifecycle.
            CollectionSchema schema = new CollectionSchema(props.getZvec().getCollection());
            FieldSchema vectorField = new FieldSchema("embedding", DataType.VECTOR_FP32, false,
                    props.getZvec().getDimension());
            try (IndexParams hnsw = IndexParams.createHNSW(MetricType.COSINE, 32, 200)) {
                vectorField.setIndexParams(hnsw);
            }
            schema.addField(vectorField);

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
            schema.close();
            ready = true;
            log.info("[Zvec] initialized at {}", dir.toAbsolutePath());
        } catch (Throwable t) {
            log.warn("[Zvec] initialization failed, vector features will be degraded: {}", t.getMessage());
            ready = false;
        }
    }

    /** 仅关闭本服务持有的 collection；runtime 关闭由 ZvecLifecycle 负责 / Close only this collection; runtime shutdown is owned by ZvecLifecycle. */
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

    /** Upsert a piece of semantic text into the vector store. */
    public void upsert(String id, String kind, String refId, String text) {
        if (!ready) return;
        try {
            float[] vector = embeddingFactory.current().embed(text);
            vector = alignDimension(vector);

            Doc doc = new Doc();
            doc.setPK(id);
            doc.addStringField("text", text);
            doc.addStringField("kind", kind);
            doc.addStringField("refId", refId);
            doc.addVectorFP32Field("embedding", vector);

            List<Doc> docs = new ArrayList<>();
            docs.add(doc);
            collection.upsert(docs);
            Doc.freeDocs(docs);
            collection.flush();
            trackedIds.computeIfAbsent(refId, k -> new ArrayList<>()).add(id);
        } catch (Exception e) {
            log.warn("[Zvec] upsert failed for {}: {}", id, e.getMessage());
        }
    }

    /** Semantic search; returns the most relevant text snippets. */
    public List<Hit> search(String query, String kindFilter, int topK) {
        if (!ready) return Collections.emptyList();
        try {
            float[] vector = alignDimension(embeddingFactory.current().embed(query));

            try (VectorQuery vq = new VectorQuery()) {
                vq.setTopK(topK);
                vq.setFieldName("embedding");
                vq.setQueryVector(vector);
                List<Doc> results = collection.query(vq);
                try {
                    List<Hit> hits = new ArrayList<>();
                    for (Doc d : results) {
                        String kind = d.getStringField("kind");
                        if (kindFilter != null && !kindFilter.isBlank() && !kindFilter.equals(kind)) continue;
                        hits.add(new Hit(d.getPK(), kind, d.getStringField("refId"),
                                d.getStringField("text"), d.getScore()));
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

    /** Convenience: remove all vectors associated with a dataset. */
    public void deleteByRefId(String refId) {
        if (!ready) return;
        try {
            List<String> toDelete = trackedIds.remove(refId);
            if (toDelete != null && !toDelete.isEmpty()) {
                collection.delete(toDelete);
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
