package com.dataagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.zvec.binding.Collection;
import org.zvec.binding.CollectionStats;
import org.zvec.binding.Doc;
import org.zvec.binding.DocIterator;
import org.zvec.binding.IteratorOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zvec collection 检视工具 / Zvec collection inspector.
 *
 * <p>提供对任意 Zvec collection 的只读查看能力：统计信息、文档列表、单文档详情。
 * 这是回答「Zvec 里到底存了什么」的核心工具。
 * Provides read-only inspection of any Zvec collection: stats, document listing,
 * and single-document detail. This is the core tool to answer "what is actually
 * stored in Zvec".
 *
 * <p>注意：Doc 背后是 native 内存，迭代和 fetch 后必须显式释放。
 * Note: Docs are backed by native memory and must be freed after iteration/fetch.
 */
@Slf4j
@Component
public class ZvecInspector {

    /**
     * 查看 collection 概览 / Inspect a collection overview.
     *
     * @param name       集合显示名 / Display name.
     * @param collection Zvec collection 对象 / The Zvec collection.
     * @param ready      集合是否可用 / Whether the collection is available.
     * @return 概览信息 / Overview map.
     */
    public Map<String, Object> overview(String name, Collection collection, boolean ready) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("ready", ready);
        if (!ready || collection == null) {
            info.put("docCount", 0);
            info.put("indexCount", 0);
            info.put("indexes", List.of());
            return info;
        }
        try {
            CollectionStats stats = collection.getStats();
            info.put("docCount", stats.getDocCount());
            info.put("indexCount", stats.getIndexCount());
            info.put("indexes", stats.getIndexNames() == null ? List.of() : stats.getIndexNames());
        } catch (Exception e) {
            log.warn("[ZvecInspector] stats failed for {}: {}", name, e.getMessage());
            info.put("error", e.getMessage());
        }
        return info;
    }

    /**
     * 列出 collection 中的文档 / List documents in a collection.
     *
     * <p>使用 DocIterator 遍历快照；limit 限制返回条数，避免大集合拖垮响应。
     * Uses DocIterator over a snapshot; limit caps the response size.
     *
     * @param collection Zvec collection / Collection.
     * @param limit      最多返回条数 / Max documents to return.
     * @return 文档摘要列表 / List of document summaries.
     */
    public List<Map<String, Object>> listDocs(Collection collection, int limit) {
        List<Map<String, Object>> docs = new ArrayList<>();
        if (collection == null) return docs;
        try (IteratorOptions opts = new IteratorOptions();
             DocIterator it = collection.createIterator(opts)) {
            int count = 0;
            while (count < limit) {
                Doc d = it.next();
                if (d == null) break;
                docs.add(docToMap(d));
                count++;
                // 每个 Doc 都需释放 / Each Doc must be freed.
                Doc.freeDocs(List.of(d));
            }
        } catch (Exception e) {
            log.warn("[ZvecInspector] list docs failed: {}", e.getMessage());
        }
        return docs;
    }

    /**
     * 查看单个文档详情（按主键 fetch）/ Fetch a single document by primary key.
     *
     * @param collection Zvec collection / Collection.
     * @param id         文档主键 / Document primary key.
     * @return 文档详情，不存在返回 null / Document detail, null if absent.
     */
    public Map<String, Object> docDetail(Collection collection, String id) {
        if (collection == null) return null;
        try {
            List<Doc> docs = collection.fetch(List.of(id));
            try {
                if (docs.isEmpty()) return null;
                return docToMap(docs.get(0));
            } finally {
                Doc.freeDocs(docs);
            }
        } catch (Exception e) {
            log.warn("[ZvecInspector] fetch failed for {}: {}", id, e.getMessage());
            return null;
        }
    }

    /** 将 Doc 转为可读 Map，向量只显示维度不输出全部数值 / Convert Doc to a readable map; vectors show dimension only. */
    private Map<String, Object> docToMap(Doc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getPK());
        m.put("score", d.getScore());
        for (String field : d.getFieldNames()) {
            if ("embedding".equals(field)) {
                float[] vec = d.getVectorFP32Field(field);
                m.put(field, vec == null ? "null" : "vector[dim=" + vec.length + "]");
            } else {
                m.put(field, d.getStringField(field));
            }
        }
        return m;
    }
}
