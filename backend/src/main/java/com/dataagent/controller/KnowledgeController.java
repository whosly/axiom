package com.dataagent.controller;

import com.dataagent.model.KnowledgeDetail;
import com.dataagent.model.KnowledgeEntry;
import com.dataagent.model.KnowledgeRequest;
import com.dataagent.service.DocumentIngestionService;
import com.dataagent.service.KnowledgeService;
import com.dataagent.service.KnowledgeVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理 API / Knowledge base management API.
 *
 * <p>提供知识条目的录入、列表、检索、删除和统计接口。
 * / Exposes endpoints for entry creation, listing, retrieval, deletion and statistics.
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService service;
    private final DocumentIngestionService documentIngestion;

    /**
     * 上传文档入库 / Upload a document into the knowledge base.
     * 支持 PDF/DOCX/MD/TXT，自动分块并生成向量 / Supports PDF/DOCX/MD/TXT with auto-chunking.
     */
    @PostMapping("/document")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "datasetId", required = false) String datasetId) {
        try {
            return ResponseEntity.ok(documentIngestion.ingest(file, datasetId));
        } catch (Exception e) {
            log.error("[Knowledge] document upload failed", e);
            return ResponseEntity.badRequest().body(java.util.Map.of("error", e.getMessage()));
        }
    }

    /** 录入一条知识 / Add a knowledge entry. */
    @PostMapping("/entry")
    public ResponseEntity<?> add(@RequestBody KnowledgeRequest req) {
        KnowledgeEntry entry = service.add(req);
        return ResponseEntity.ok(Map.of("entry", entry));
    }

    /** 列出所有条目 / List all entries. */
    @GetMapping
    public Map<String, Object> list() {
        return Map.of("entries", service.listAll(), "stats", service.stats());
    }

    /** 按类型列出 / List by kind. */
    @GetMapping("/kind/{kind}")
    public Map<String, Object> listByKind(@PathVariable String kind) {
        return Map.of("entries", service.listByKind(kind));
    }

    /**
     * 查看单条知识的完整详情 / View full detail of a single knowledge entry.
     * 包含元数据、向量状态、溯源与处理链路 / Includes metadata, vector status, traceability and lineage.
     */
    @GetMapping("/{id}/detail")
    public ResponseEntity<?> detail(@PathVariable String id) {
        KnowledgeDetail detail = service.getDetail(id);
        return detail == null
                ? ResponseEntity.status(404).body(Map.of("error", "知识条目不存在"))
                : ResponseEntity.ok(Map.of("detail", detail));
    }

    /** 检索测试接口 / Retrieval test endpoint. */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        String query = String.valueOf(body.getOrDefault("query", ""));
        String kind = body.get("kind") == null ? null : String.valueOf(body.get("kind"));
        String datasetId = body.get("datasetId") == null ? null : String.valueOf(body.get("datasetId"));
        int topK = body.get("topK") == null ? 5 : Integer.parseInt(String.valueOf(body.get("topK")));
        List<KnowledgeVectorStore.Hit> hits = service.search(query, kind, datasetId, topK);
        return Map.of("hits", hits, "count", hits.size());
    }

    /** 列出知识来源（批次）/ List knowledge sources (batches). */
    @GetMapping("/sources")
    public Map<String, Object> sources() {
        return Map.of("sources", service.listSources());
    }

    /** 删除知识来源及其全部条目 / Delete a source and all its entries. */
    @DeleteMapping("/sources/{sourceId}")
    public Map<String, Object> deleteSource(@PathVariable String sourceId) {
        return Map.of("success", true, "deleted", service.deleteSource(sourceId));
    }

    /** 双写状态统计（一致性监控）/ Dual-write status statistics. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("statuses", service.statusStats());
    }

    /** 重建向量索引（一致性修复）/ Rebuild vector index (consistency repair). */
    @PostMapping("/reindex")
    public Map<String, Object> reindex(@RequestParam(defaultValue = "false") boolean force) {
        return service.reindex(force);
    }

    /** 删除单条（软删除）/ Soft-delete one entry. */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return Map.of("success", true);
    }

    /** 恢复软删除的条目 / Restore a soft-deleted entry. */
    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id) {
        return Map.of("success", service.restore(id));
    }

    /** 按来源删除 / Delete by source. */
    @DeleteMapping("/source/{sourceId}")
    public Map<String, Object> deleteBySource(@PathVariable String sourceId) {
        int count = service.deleteBySource(sourceId);
        return Map.of("success", true, "deleted", count);
    }
}
