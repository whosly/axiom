package com.dataagent.controller;

import com.dataagent.service.KnowledgeVectorStore;
import com.dataagent.service.VectorService;
import com.dataagent.service.ZvecInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zvec 数据查看 API / Zvec data inspection API.
 *
 * <p>回答「Zvec 里到底存了什么」：列出两个 collection 的概览、文档、单文档详情。
 * / Answers "what is stored in Zvec": overview, documents and per-document detail
 * for both collections.
 */
@Slf4j
@RestController
@RequestMapping("/api/zvec")
@RequiredArgsConstructor
public class ZvecController {

    private final VectorService vectorService;
    private final KnowledgeVectorStore knowledgeStore;
    private final ZvecInspector inspector;

    /** 列出所有 collection 概览 / List overview of all collections. */
    @GetMapping("/collections")
    public Map<String, Object> collections() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(inspector.overview("dataset_semantics", vectorService.rawCollection(), vectorService.isReady()));
        list.add(inspector.overview("knowledge_base", knowledgeStore.rawCollection(), knowledgeStore.isReady()));
        return Map.of("collections", list);
    }

    /**
     * 列出某 collection 的文档 / List documents of a collection.
     *
     * @param name  collection 名称（dataset_semantics | knowledge_base）/ Collection name.
     * @param limit 最多返回条数 / Max docs.
     */
    @GetMapping("/collections/{name}/docs")
    public Map<String, Object> docs(@PathVariable String name,
                                    @RequestParam(defaultValue = "50") int limit) {
        org.zvec.binding.Collection coll = resolve(name);
        if (coll == null) return Map.of("docs", List.of(), "error", "collection 不可用");
        return Map.of("docs", inspector.listDocs(coll, Math.min(limit, 500)));
    }

    /**
     * 查看单文档详情 / View a single document.
     *
     * @param name collection 名称 / Collection name.
     * @param id   文档主键 / Document ID.
     */
    @GetMapping("/collections/{name}/docs/{id}")
    public Map<String, Object> doc(@PathVariable String name, @PathVariable String id) {
        org.zvec.binding.Collection coll = resolve(name);
        if (coll == null) return Map.of("error", "collection 不可用");
        Map<String, Object> doc = inspector.docDetail(coll, id);
        return doc == null ? Map.of("error", "文档不存在") : Map.of("doc", doc);
    }

    /** 根据名称解析 collection / Resolve collection by name. */
    private org.zvec.binding.Collection resolve(String name) {
        return switch (name) {
            case "dataset_semantics" -> vectorService.isReady() ? vectorService.rawCollection() : null;
            case "knowledge_base" -> knowledgeStore.isReady() ? knowledgeStore.rawCollection() : null;
            default -> null;
        };
    }
}
