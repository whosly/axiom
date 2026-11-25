package com.axiom.controller;

import com.axiom.service.KnowledgeVectorStore;
import com.axiom.service.VectorService;
import com.axiom.service.ZvecInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Zvec 数据查看 API / Zvec data inspection API.
 *
 * <p>回答「Zvec 里到底存了什么」：列出两个 collection 的概览、文档、单文档详情。
 * 所有 native 调用都通过所属服务的串行锁执行（{@code inspect}），与写入/flush 互斥。
 * / Answers "what is stored in Zvec": overview, documents and per-document detail
 * for both collections. All native calls run through the owning service's serial
 * lock so inspection never races with writes or flushes.
 */
@Slf4j
@RestController
@RequestMapping("/api/zvec")
@RequiredArgsConstructor
public class ZvecController {

    private final VectorService vectorService;
    private final KnowledgeVectorStore knowledgeStore;
    private final ZvecInspector inspector;
    private final com.axiom.config.AppProperties props;

    /** 列出所有 collection 概览 / List overview of all collections. */
    @GetMapping("/collections")
    public Map<String, Object> collections() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(vectorService.inspect(() ->
                inspector.overview(props.getZvec().getCollection(), vectorService.rawCollection(), vectorService.isReady())));
        list.add(knowledgeStore.inspect(() ->
                inspector.overview(props.getZvec().getKnowledgeCollection(), knowledgeStore.rawCollection(), knowledgeStore.isReady())));
        return Map.of("collections", list);
    }

    /**
     * 列出某 collection 的文档 / List documents of a collection.
     *
     * @param name  collection 名称（配置中的 collection / knowledge-collection）/ Collection name.
     * @param limit 最多返回条数 / Max docs.
     */
    @GetMapping("/collections/{name}/docs")
    public Map<String, Object> docs(@PathVariable String name,
                                    @RequestParam(defaultValue = "50") int limit) {
        Object docs = withCollection(name, coll -> inspector.listDocs(coll, Math.min(limit, 500)));
        if (docs == null) return Map.of("docs", List.of(), "error", "collection 不可用");
        return Map.of("docs", docs);
    }

    /**
     * 查看单文档详情 / View a single document.
     *
     * @param name collection 名称 / Collection name.
     * @param id   文档主键 / Document ID.
     */
    @GetMapping("/collections/{name}/docs/{id}")
    public Map<String, Object> doc(@PathVariable String name, @PathVariable String id) {
        // 先区分「collection 不可用/未知」与「文档不存在」两种 null 来源，
        // 否则排查方向会被误导。/ Disambiguate "collection unavailable" from
        // "document not found" — both map to null downstream.
        if (!collectionAvailable(name)) return Map.of("error", "collection 不可用");
        Object doc = withCollection(name, coll -> inspector.docDetail(coll, id));
        if (doc == null) return Map.of("error", "文档不存在");
        return Map.of("doc", doc);
    }

    /** collection 是否已知且就绪 / Whether the named collection is known and ready. */
    private boolean collectionAvailable(String name) {
        if (props.getZvec().getCollection().equals(name)) return vectorService.isReady();
        if (props.getZvec().getKnowledgeCollection().equals(name)) return knowledgeStore.isReady();
        return false;
    }

    /**
     * 按名称定位 collection 并在所属服务的串行锁内执行操作；不可用返回 null。
     * / Resolve the collection by name and run the action under the owning
     * service's serial lock; null when the collection is unavailable.
     */
    private Object withCollection(String name, Function<org.zvec.binding.Collection, Object> action) {
        if (props.getZvec().getCollection().equals(name)) {
            if (!vectorService.isReady()) return null;
            return vectorService.inspect(() -> action.apply(vectorService.rawCollection()));
        }
        if (props.getZvec().getKnowledgeCollection().equals(name)) {
            if (!knowledgeStore.isReady()) return null;
            return knowledgeStore.inspect(() -> action.apply(knowledgeStore.rawCollection()));
        }
        return null;
    }
}
