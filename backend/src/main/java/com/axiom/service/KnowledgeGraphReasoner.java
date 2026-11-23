package com.axiom.service;

import com.axiom.graph.GraphSnapshot;
import com.axiom.graph.GraphSnapshotCache;
import com.axiom.graph.GraphSnapshotCache.SnapshotView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识图谱推理引擎 / Knowledge graph reasoning engine.
 *
 * <p>提供社区发现、最短路径与关联分析三种能力。基于 {@link GraphSnapshotCache} 的
 * 版本化紧凑快照：图结构不变时零数据库访问；万级节点按度数采样保护；社区结果按
 * (version, maxIterations) 缓存，重复请求 O(1)。算法在 int 索引数组上运行，避免
 * Map<String,…> 的装箱与字符串哈希开销。
 * All algorithms run on int-indexed arrays from a versioned compact snapshot, with
 * top-degree sampling for large graphs and cached community results.
 */
@Slf4j
@Service
public class KnowledgeGraphReasoner {

    private final GraphSnapshotCache cache;

    /** 社区结果缓存键 / Cache key for community results. */
    private record CommunityKey(long version, int maxIterations) {}

    private final AtomicReference<Map.Entry<CommunityKey, Map<String, Object>>> communityCache =
            new AtomicReference<>();

    public KnowledgeGraphReasoner(GraphSnapshotCache cache) {
        this.cache = cache;
    }

    /**
     * 社区发现（标签传播）/ Community detection via label propagation.
     *
     * @param maxIterations 最大迭代次数（1–50）/ Max iterations.
     * @return 社区列表、数量、迭代次数及采样元数据 / Communities, count, iterations and sampling metadata.
     */
    public Map<String, Object> communities(int maxIterations) {
        // 缓存键与实际算法使用同一钳制值，避免 100 与 50 产生两份相同结果。
        // Use the same clamped value for the cache key as the algorithm to avoid duplicate entries.
        int effectiveIterations = Math.max(1, Math.min(maxIterations, 50));
        SnapshotView view = cache.get();
        GraphSnapshot g = view.snapshot();
        CommunityKey key = new CommunityKey(view.version(), effectiveIterations);
        Map.Entry<CommunityKey, Map<String, Object>> cached = communityCache.get();
        if (cached != null && cached.getKey().equals(key)) return cached.getValue();

        Map<String, Object> result = detectCommunities(g, effectiveIterations);
        // 附加采样元数据 / Attach sampling metadata.
        Map<String, Object> enriched = new LinkedHashMap<>(result);
        enriched.put("sampled", view.sampled());
        enriched.put("totalNodes", view.totalNodes());
        enriched.put("analyzedNodes", g.size);
        enriched.put("version", view.version());
        communityCache.set(Map.entry(key, enriched));
        log.info("[Reasoner] communities={} iterations={} sampled={} nodes={}/{}",
                enriched.get("count"), enriched.get("iterations"), view.sampled(), g.size, view.totalNodes());
        return enriched;
    }

    /** 标签传播核心算法（int 数组实现）/ Label propagation core on int arrays. */
    private Map<String, Object> detectCommunities(GraphSnapshot g, int maxIterations) {
        int n = g.size;
        if (n == 0) return Map.of("communities", List.of(), "count", 0, "iterations", 0);

        // 初始标签 = 自身索引 / Initial label = own index.
        int[] label = new int[n];
        for (int i = 0; i < n; i++) label[i] = i;
        int[] freqLabel = new int[n];   // 复用的候选标签缓冲 / Reusable label buffer.
        int[] freqCount = new int[n];   // 复用的计数缓冲 / Reusable count buffer.

        int iterations = Math.max(1, Math.min(maxIterations, 50));
        int used = 0;
        for (int iter = 0; iter < iterations; iter++) {
            boolean changed = false;
            // 按 ID 字典序处理保证稳定（快照 ids 已由 Repository ORDER BY 排序）
            // Process in snapshot order for determinism (Repository orders by type, id).
            for (int i = 0; i < n; i++) {
                int cnt = 0;
                for (int nb : g.adjacency[i]) {
                    int l = label[nb];
                    int found = -1;
                    for (int k = 0; k < cnt; k++) {
                        if (freqLabel[k] == l) { found = k; break; }
                    }
                    if (found < 0) {
                        if (cnt < freqLabel.length) {
                            freqLabel[cnt] = l;
                            freqCount[cnt] = 1;
                            cnt++;
                        }
                    } else {
                        freqCount[found]++;
                    }
                }
                if (cnt == 0) continue;
                // 选计数最高的标签，平局取标签值更大者（稳定）/ Highest count; tie-break by larger label.
                int bestLabel = freqLabel[0], bestCount = freqCount[0];
                for (int k = 1; k < cnt; k++) {
                    if (freqCount[k] > bestCount || (freqCount[k] == bestCount && freqLabel[k] > bestLabel)) {
                        bestLabel = freqLabel[k];
                        bestCount = freqCount[k];
                    }
                }
                if (bestLabel != label[i]) { label[i] = bestLabel; changed = true; }
            }
            used = iter + 1;
            if (!changed) break;
        }

        // 聚合社区 / Aggregate communities.
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(label[i], k -> new ArrayList<>()).add(i);
        List<Map<String, Object>> communities = new ArrayList<>();
        for (List<Integer> members : groups.values()) {
            Map<String, Integer> types = new HashMap<>();
            for (int m : members) types.merge(g.types[m], 1, Integer::sum);
            List<Map<String, String>> memberViews = new ArrayList<>(members.size());
            for (int m : members) {
                memberViews.add(Map.of("id", g.ids[m], "label", g.labels[m]));
            }
            communities.add(Map.of("size", members.size(), "members", memberViews, "typeDistribution", types));
        }
        communities.sort(Comparator.comparingInt((Map<String, Object> c) -> (int) c.get("size")).reversed());
        return Map.of("communities", communities, "count", communities.size(), "iterations", used);
    }

    /**
     * 最短路径（BFS）/ Shortest path via BFS.
     *
     * @param from 起始节点 / Start node.
     * @param to   目标节点 / Target node.
     * @return 路径节点列表与可达性 / Path nodes and reachability.
     */
    public Map<String, Object> shortestPath(String from, String to) {
        GraphSnapshot g = cache.get().snapshot();
        int s = g.indexOf(from), t = g.indexOf(to);
        if (s < 0 || t < 0) {
            return Map.of("path", List.of(), "length", -1, "reachable", false,
                    "message", "节点不存在 / Node not found");
        }
        int[] prev = new int[g.size];
        Arrays.fill(prev, -1);
        boolean[] visited = new boolean[g.size];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(s);
        visited[s] = true;
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (cur == t) break;
            for (int nb : g.adjacency[cur]) {
                if (!visited[nb]) { visited[nb] = true; prev[nb] = cur; queue.add(nb); }
            }
        }
        if (!visited[t]) return Map.of("path", List.of(), "length", -1, "reachable", false);

        List<Integer> path = new ArrayList<>();
        for (int at = t; at != -1; at = prev[at]) {
            path.add(at);
            if (at == s) break;
        }
        java.util.Collections.reverse(path);
        List<Map<String, String>> nodes = new ArrayList<>(path.size());
        for (int i : path) {
            nodes.add(Map.of("id", g.ids[i], "label", g.labels[i], "type", g.types[i]));
        }
        return Map.of("path", nodes, "length", path.size() - 1, "reachable", true);
    }

    /**
     * 关联分析：共享字段/标签的间接关联 / Related knowledge via shared relations.
     *
     * @param entryId 知识条目 ID / Entry ID.
     * @param limit   返回上限（1–50）/ Result limit.
     * @return 关联知识列表 / Related entries.
     */
    public Map<String, Object> related(String entryId, int limit) {
        if (entryId == null || entryId.isBlank()) {
            throw new IllegalArgumentException("entryId 不能为空 / entryId is required");
        }
        GraphSnapshot g = cache.get().snapshot();
        String node = entryId.startsWith("knowledge:") ? entryId : "knowledge:" + entryId;
        int start = g.indexOf(node);
        if (start < 0) return Map.of("related", List.of(), "count", 0);

        Map<Integer, Integer> score = new HashMap<>();
        for (int mid : g.adjacency[start]) {
            String type = g.types[mid];
            if (type == null || type.isEmpty() || type.equals("knowledge")) continue;
            for (int other : g.adjacency[mid]) {
                if (other != start && "knowledge".equals(g.types[other])) {
                    score.merge(other, 1, Integer::sum);
                }
            }
        }
        List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(score.entrySet());
        ranked.sort(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> g.ids[e.getKey()]));
        List<Map<String, Object>> related = new ArrayList<>();
        int cap = Math.max(1, Math.min(limit, 50));
        for (Map.Entry<Integer, Integer> e : ranked) {
            if (related.size() >= cap) break;
            int i = e.getKey();
            related.add(Map.of("id", g.ids[i], "label", g.labels[i], "sharedRelations", e.getValue()));
        }
        return Map.of("related", related, "count", related.size());
    }
}
