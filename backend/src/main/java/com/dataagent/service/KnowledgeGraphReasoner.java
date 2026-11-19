package com.dataagent.service;

import com.dataagent.repository.KnowledgeGraphRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 知识图谱推理引擎 / Knowledge graph reasoning engine.
 *
 * <p>提供三种图推理能力：
 * Provides three graph reasoning capabilities:
 * <ol>
 *   <li>社区发现：标签传播算法，找出紧密关联的知识簇 / Community detection via label propagation</li>
 *   <li>最短路径：BFS 找两节点间关系链 / Shortest path via BFS</li>
 *   <li>关联分析：找共享字段/标签的间接关联知识 / Related knowledge via shared fields/tags</li>
 * </ol>
 * 所有算法在 Java 内存中基于节点/边列表运行，无需图数据库。
 * All algorithms run in Java memory over node/edge lists; no graph DB needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphReasoner {

    private final KnowledgeGraphRepository graphRepo;

    /** 内部轻量图结构 / Lightweight in-memory graph. */
    private static class Graph {
        Map<String, String> labels = new HashMap<>();       // id -> type
        Map<String, String> names = new HashMap<>();        // id -> label
        Map<String, List<String>> adj = new HashMap<>();    // id -> neighbors
    }

    /** 从 Repository 加载图 / Load graph from repository. */
    private Graph load() {
        Graph g = new Graph();
        for (Map<String, Object> n : graphRepo.listNodes()) {
            String id = str(ci(n, "id"));
            g.labels.put(id, str(ci(n, "type")));
            g.names.put(id, str(ci(n, "label")));
            g.adj.computeIfAbsent(id, k -> new ArrayList<>());
        }
        for (Map<String, Object> e : graphRepo.listEdges()) {
            String s = str(ci(e, "source")), t = str(ci(e, "target"));
            g.adj.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
            g.adj.computeIfAbsent(t, k -> new ArrayList<>()).add(s);
        }
        return g;
    }

    /**
     * 社区发现（标签传播算法）/ Community detection (label propagation).
     *
     * <p>每个节点初始标签为自己，迭代地将标签更新为邻居中出现最多的标签，
     * 直到收敛。同一标签的节点构成一个社区。
     * / Each node starts with its own label; iteratively adopts the most frequent
     * neighbor label until convergence. Nodes sharing a label form a community.
     *
     * @param maxIterations 最大迭代次数 / Max iterations.
     * @return 社区列表，每个含成员与类型分布 / Communities with members and type distribution.
     */
    public Map<String, Object> communities(int maxIterations) {
        Graph g = load();
        Map<String, String> label = new HashMap<>();
        for (String id : g.labels.keySet()) label.put(id, id);
        int iter = 0;
        for (; iter < Math.max(1, Math.min(maxIterations, 50)); iter++) {
            boolean changed = false;
            // 按 ID 排序保证结果稳定 / Sort IDs for deterministic results.
            List<String> ids = new ArrayList<>(g.labels.keySet());
            Collections.sort(ids);
            for (String id : ids) {
                Map<String, Integer> freq = new HashMap<>();
                for (String nb : g.adj.getOrDefault(id, List.of())) {
                    String l = label.get(nb);
                    if (l != null) freq.merge(l, 1, Integer::sum);
                }
                if (freq.isEmpty()) continue;
                String best = freq.entrySet().stream()
                        .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                                .thenComparing(e -> e.getKey(), Comparator.reverseOrder()))
                        .map(Map.Entry::getKey).orElse(label.get(id));
                if (!best.equals(label.get(id))) { label.put(id, best); changed = true; }
            }
            if (!changed) break;
        }

        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : label.entrySet()) {
            groups.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        List<Map<String, Object>> communities = new ArrayList<>();
        for (List<String> members : groups.values()) {
            Map<String, Integer> types = new HashMap<>();
            for (String m : members) types.merge(g.labels.getOrDefault(m, "?"), 1, Integer::sum);
            communities.add(Map.of(
                    "size", members.size(),
                    "members", members.stream().map(m -> Map.of("id", m, "label", g.names.getOrDefault(m, m))).toList(),
                    "typeDistribution", types));
        }
        communities.sort((a, b) -> (int) b.get("size") - (int) a.get("size"));
        log.info("[Reasoner] communities={} iterations={}", communities.size(), iter + 1);
        return Map.of("communities", communities, "count", communities.size(), "iterations", iter + 1);
    }

    /**
     *  shortest path between two nodes (BFS).
     *
     * @param from 起始节点 ID / Start node ID.
     * @param to   目标节点 ID / Target node ID.
     * @return 路径节点列表（含起止），不可达返回空 / Path nodes, empty if unreachable.
     */
    public Map<String, Object> shortestPath(String from, String to) {
        Graph g = load();
        if (!g.labels.containsKey(from) || !g.labels.containsKey(to)) {
            return Map.of("path", List.of(), "length", -1, "reachable", false,
                    "message", "节点不存在 / Node not found");
        }
        Map<String, String> prev = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        visited.add(from);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(to)) break;
            for (String nb : g.adj.getOrDefault(cur, List.of())) {
                if (!visited.contains(nb)) { visited.add(nb); prev.put(nb, cur); queue.add(nb); }
            }
        }
        if (!visited.contains(to)) return Map.of("path", List.of(), "length", -1, "reachable", false);

        List<String> path = new ArrayList<>();
        for (String at = to; at != null; at = prev.get(at)) {
            path.add(at);
            if (at.equals(from)) break;
        }
        Collections.reverse(path);
        List<Map<String, String>> nodes = path.stream()
                .map(id -> Map.of("id", id, "label", g.names.getOrDefault(id, id),
                        "type", g.labels.getOrDefault(id, "?"))).toList();
        return Map.of("path", nodes, "length", path.size() - 1, "reachable", true);
    }

    /**
     * 关联分析：找与某知识共享字段/标签的其他知识 / Related knowledge via shared fields/tags.
     *
     * @param entryId 知识条目 ID（knowledge:&lt;id&gt;）/ Knowledge node ID.
     * @return 关联知识列表（按共享关系数排序）/ Related knowledge sorted by shared-relation count.
     */
    public Map<String, Object> related(String entryId, int limit) {
        Graph g = load();
        String node = entryId.startsWith("knowledge:") ? entryId : "knowledge:" + entryId;
        if (!g.labels.containsKey(node)) return Map.of("related", List.of(), "count", 0);

        // 一跳邻居中的 field/tag/intent/dataset，再找它们的其他 knowledge 邻居。
        // 1-hop field/tag/intent/dataset nodes, then their other knowledge neighbors.
        Map<String, Integer> score = new HashMap<>();
        for (String mid : g.adj.getOrDefault(node, List.of())) {
            String type = g.labels.get(mid);
            if (type == null || type.equals("knowledge")) continue;
            for (String other : g.adj.getOrDefault(mid, List.of())) {
                if (other.equals(node) || !"knowledge".equals(g.labels.get(other))) continue;
                score.merge(other, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> related = new ArrayList<>();
        score.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey).reversed())
                .limit(Math.max(1, Math.min(limit, 50)))
                .forEach(e -> related.add(Map.of(
                        "id", e.getKey(), "label", g.names.getOrDefault(e.getKey(), e.getKey()),
                        "sharedRelations", e.getValue())));
        return Map.of("related", related, "count", related.size());
    }

    // ---- helpers ----

    /** 大小写不敏感读取 / Case-insensitive map lookup. */
    private Object ci(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private String str(Object v) { return v == null ? "" : String.valueOf(v); }
}
