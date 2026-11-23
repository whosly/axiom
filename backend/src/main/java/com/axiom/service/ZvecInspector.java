package com.axiom.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.zvec.binding.Collection;
import org.zvec.binding.CollectionSchema;
import org.zvec.binding.DataType;
import org.zvec.binding.Doc;
import org.zvec.binding.DocIterator;
import org.zvec.binding.FieldSchema;
import org.zvec.binding.IteratorOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zvec collection 检视工具 / Zvec collection inspector.
 *
 * <p>提供对任意 Zvec collection 的只读查看能力：统计信息、文档列表、单文档详情。
 * Provides read-only inspection of any Zvec collection: stats, document listing,
 * and single-document detail.
 *
 * <p><b>关键点：按 schema 字段类型读取。</b> Zvec Doc 的 getXxxField 必须与字段声明类型
 * 严格匹配（如 INT32 用 getInt32Field），否则 native 抛 "Field type mismatch"。
 * 旧实现无差别调用 getStringField，遇到 chunkIndex(INT32) 会失败并导致整个列表为空。
 * <b>Key: read fields by their schema DataType.</b> Calling a mismatched getter (e.g.
 * getStringField on an INT32 field) throws a native "Field type mismatch" error, which
 * previously made the entire document list appear empty.
 *
 * <p>注意：Doc/Schema 背后是 native 内存，迭代与 fetch 后必须显式释放。
 * Docs and schemas are backed by native memory and must be freed after use.
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
            info.put("docCount", collection.getStats().getDocCount());
            info.put("indexCount", collection.getStats().getIndexCount());
            var names = collection.getStats().getIndexNames();
            info.put("indexes", names == null ? List.of() : names);
        } catch (Exception e) {
            log.warn("[ZvecInspector] stats failed for {}: {}", name, e.getMessage());
            info.put("error", e.getMessage());
        }
        return info;
    }

    /**
     * 列出 collection 中的文档 / List documents in a collection.
     *
     * @param collection Zvec collection / Collection.
     * @param limit      最多返回条数 / Max documents to return.
     * @return 文档摘要列表（单文档失败不影响其他文档）/ Document summaries; one bad doc does not drop the rest.
     */
    public List<Map<String, Object>> listDocs(Collection collection, int limit) {
        List<Map<String, Object>> docs = new ArrayList<>();
        if (collection == null) return docs;
        Map<String, DataType> types = fieldTypes(collection);
        try (IteratorOptions opts = new IteratorOptions();
             DocIterator it = collection.createIterator(opts)) {
            int count = 0;
            while (count < limit) {
                Doc d = it.next();
                if (d == null) break;
                try {
                    docs.add(docToMap(d, types));
                    count++;
                } catch (Exception e) {
                    // 单文档解析失败只跳过该文档，不终止整个列表 / Skip only the failing document.
                    log.warn("[ZvecInspector] skip doc during listing: {}", e.getMessage());
                } finally {
                    Doc.freeDocs(List.of(d));
                }
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
        Map<String, DataType> types = fieldTypes(collection);
        try {
            List<Doc> docs = collection.fetch(List.of(id));
            try {
                if (docs.isEmpty()) return null;
                return docToMap(docs.get(0), types);
            } finally {
                Doc.freeDocs(docs);
            }
        } catch (Exception e) {
            log.warn("[ZvecInspector] fetch failed for {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 读取 collection 的字段名→类型映射 / Build field-name to DataType map from schema.
     */
    private Map<String, DataType> fieldTypes(Collection collection) {
        Map<String, DataType> types = new HashMap<>();
        try (CollectionSchema schema = collection.getSchema()) {
            for (String name : schema.getAllFieldNames()) {
                FieldSchema f = schema.getField(name);
                if (f != null) {
                    types.put(name, f.getDataType());
                    f.close();
                }
            }
        } catch (Exception e) {
            log.warn("[ZvecInspector] read schema failed: {}", e.getMessage());
        }
        return types;
    }

    /**
     * 将 Doc 转为可读 Map，按字段类型读取；向量只显示维度不输出全部数值。
     * Convert Doc to a readable map reading each field by its declared type; vectors show dimension only.
     */
    private Map<String, Object> docToMap(Doc d, Map<String, DataType> types) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getPK());
        m.put("score", d.getScore());
        for (String field : d.getFieldNames()) {
            if (d.isFieldNull(field)) { m.put(field, null); continue; }
            DataType t = types.get(field);
            try {
                m.put(field, readField(d, field, t));
            } catch (Exception e) {
                // 单个字段类型不匹配时降级为字符串标记，不影响整条文档
                // On a per-field type mismatch, mark it without dropping the whole doc.
                log.debug("[ZvecInspector] field {} read failed: {}", field, e.getMessage());
                m.put(field, "<无法读取: " + e.getMessage() + ">");
            }
        }
        return m;
    }

    /** 按 DataType 读取字段值 / Read a field value according to its DataType. */
    private Object readField(Doc d, String field, DataType t) {
        if (t == null) {
            // 未知类型时先试字符串 / Unknown type: try string first.
            return d.getStringField(field);
        }
        return switch (t) {
            case STRING -> d.getStringField(field);
            case BOOL -> d.getBoolField(field);
            case INT32 -> d.getInt32Field(field);
            case INT64 -> d.getInt64Field(field);
            case UINT32 -> d.getUint32Field(field);
            case UINT64 -> d.getUint64Field(field);
            case FLOAT -> d.getFloatField(field);
            case DOUBLE -> d.getDoubleField(field);
            case VECTOR_FP32 -> vectorDim(d.getVectorFP32Field(field));
            case VECTOR_FP64, VECTOR_FP16, VECTOR_INT4, VECTOR_INT8, VECTOR_INT16, VECTOR_UINT8,
                 VECTOR_BINARY32, VECTOR_BINARY64, SPARSE_VECTOR_FP16, SPARSE_VECTOR_FP32 -> "vector[complex]";
            case BINARY -> binaryLen(d.getBinaryField(field));
            default -> d.getStringField(field);
        };
    }

    private String vectorDim(float[] vec) {
        return vec == null ? "null" : "vector[dim=" + vec.length + "]";
    }

    private String binaryLen(byte[] b) {
        return b == null ? "null" : "binary[bytes=" + b.length + "]";
    }
}
