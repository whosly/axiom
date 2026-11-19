package com.dataagent.controller;

import com.dataagent.service.KnowledgeGraphReasoner;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 知识图谱推理 API / Knowledge graph reasoning API.
 */
@RestController
@RequestMapping("/api/knowledge/graph/reason")
@RequiredArgsConstructor
public class KnowledgeGraphReasonerController {

    private final KnowledgeGraphReasoner reasoner;

    /** 社区发现 / Community detection. */
    @GetMapping("/communities")
    public Map<String, Object> communities(@RequestParam(defaultValue = "20") int maxIterations) {
        return reasoner.communities(maxIterations);
    }

    /** 最短路径 / Shortest path. */
    @GetMapping("/path")
    public Map<String, Object> path(@RequestParam String from, @RequestParam String to) {
        return reasoner.shortestPath(from, to);
    }

    /** 关联分析 / Related knowledge. */
    @GetMapping("/related")
    public Map<String, Object> related(@RequestParam String entryId,
                                       @RequestParam(defaultValue = "10") int limit) {
        return reasoner.related(entryId, limit);
    }
}
