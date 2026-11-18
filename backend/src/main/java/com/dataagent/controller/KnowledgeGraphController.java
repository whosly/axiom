package com.dataagent.controller;

import com.dataagent.service.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 知识图谱 API / Knowledge graph API.
 *
 * <p>提供图谱重建、查看、邻居查询接口，供前端可视化使用。
 * / Exposes graph rebuild, view and neighbor query endpoints for frontend visualization.
 */
@RestController
@RequestMapping("/api/knowledge/graph")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService service;

    /** 重建图谱 / Rebuild the graph. */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() { return service.rebuild(); }

    /** 完整图数据 / Full graph data. */
    @GetMapping
    public Map<String, Object> graph() { return service.graph(); }

    /** 节点列表 / Node list. */
    @GetMapping("/nodes")
    public Map<String, Object> nodes() { return service.nodes(); }

    /** 边列表 / Edge list. */
    @GetMapping("/edges")
    public Map<String, Object> edges() { return service.edges(); }

    /** N 跳邻居 / N-hop neighbors. */
    @GetMapping("/neighbors")
    public Map<String, Object> neighbors(@RequestParam String nodeId,
                                         @RequestParam(defaultValue = "1") int hops) {
        return service.neighbors(nodeId, hops);
    }

    /** 统计 / Statistics. */
    @GetMapping("/stats")
    public Map<String, Object> stats() { return service.stats(); }
}
