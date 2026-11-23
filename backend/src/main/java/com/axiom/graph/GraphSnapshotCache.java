package com.axiom.graph;

import com.axiom.repository.KnowledgeGraphRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 图谱快照缓存 / Graph snapshot cache.
 *
 * <p>按图版本号缓存紧凑快照与社区发现结果：版本不变时推理请求零数据库访问、零重建。
 * 超过 {@code maxNodes} 时按度数 Top-K 采样子图，避免万级节点把内存与算法拖垮；
 * 返回元数据标记采样状态，前端可提示结果基于子图。
 * Caches a compact snapshot and community results by graph version: with an unchanged
 * version, reasoning requests do zero DB reads. Graphs above maxNodes are sampled by
 * top-degree nodes to bound memory/CPU, with metadata flags for the caller.
 */
@Slf4j
@Service
public class GraphSnapshotCache {

    /** 图节点上限，超过则采样（生产可调）/ Node cap; larger graphs are sampled. */
    @Value("${app.graph.max-nodes:10000}")
    private int maxNodes = 10000;

    /** 采样保留的节点数 / Number of nodes kept when sampling. */
    @Value("${app.graph.sample-size:3000}")
    private int sampleSize = 3000;

    private final KnowledgeGraphRepository repo;

    /** 缓存条目：版本 + 快照 + 采样元数据 / Cached entry: version, snapshot and sampling metadata. */
    private static class Cached {
        final long version;
        final GraphSnapshot snapshot;
        final boolean sampled;
        final int totalNodes;
        Cached(long version, GraphSnapshot snapshot, boolean sampled, int totalNodes) {
            this.version = version;
            this.snapshot = snapshot;
            this.sampled = sampled;
            this.totalNodes = totalNodes;
        }
    }

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public GraphSnapshotCache(KnowledgeGraphRepository repo) {
        this.repo = repo;
    }

    /**
     * 获取当前图快照（版本命中则复用）/ Get current snapshot, rebuilt only on version change.
     *
     * @return 快照视图 / Snapshot view.
     */
    public SnapshotView get() {
        long version = repo.version();
        Cached current = cache.get();
        if (current != null && current.version == version) return view(current);

        // 双检锁：并发重建只发生一次 / Double-check so concurrent rebuilds happen once.
        synchronized (this) {
            current = cache.get();
            if (current != null && current.version == version) return view(current);
            Cached built = build(version);
            cache.set(built);
            log.info("[GraphCache] snapshot rebuilt: version={} nodes={} sampled={}",
                    built.version, built.snapshot.size, built.sampled);
            return view(built);
        }
    }

    /** 构建快照：小图全量、大图按度数采样 / Build snapshot: full for small graphs, top-degree sample for large. */
    private Cached build(long version) {
        List<Map<String, Object>> nodeRows = repo.listNodes();
        List<Map<String, Object>> edgeRows = repo.listEdges();
        int total = nodeRows.size();

        if (total <= maxNodes) {
            GraphSnapshot.Builder b = new GraphSnapshot.Builder();
            for (Map<String, Object> n : nodeRows) {
                b.addNode(str(ci(n, "id")), str(ci(n, "type")), str(ci(n, "label")));
            }
            for (Map<String, Object> e : edgeRows) {
                b.addEdge(str(ci(e, "source")), str(ci(e, "target")));
            }
            return new Cached(version, b.build(), false, total);
        }

        // 大图：先用边计算度数，选 Top-K 高连接节点作为采样核心
        // Large graph: compute degrees from edges, keep top-K high-degree nodes as the sample core.
        Map<String, Integer> degree = new java.util.HashMap<>();
        for (Map<String, Object> e : edgeRows) {
            String s = str(ci(e, "source")), t = str(ci(e, "target"));
            degree.merge(s, 1, Integer::sum);
            degree.merge(t, 1, Integer::sum);
        }
        List<String> kept = new ArrayList<>(degree.keySet());
        kept.sort(Comparator.<String>comparingInt(degree::get).reversed()
                .thenComparing(Comparator.naturalOrder()));
        int keep = Math.min(sampleSize, kept.size());
        java.util.Set<String> keepSet = new java.util.HashSet<>(kept.subList(0, keep));

        GraphSnapshot.Builder b = new GraphSnapshot.Builder();
        for (Map<String, Object> n : nodeRows) {
            String id = str(ci(n, "id"));
            if (keepSet.contains(id)) b.addNode(id, str(ci(n, "type")), str(ci(n, "label")));
        }
        for (Map<String, Object> e : edgeRows) {
            String s = str(ci(e, "source")), t = str(ci(e, "target"));
            // 只保留两端都在采样集内的边 / Keep only edges whose both endpoints are sampled.
            if (keepSet.contains(s) && keepSet.contains(t)) b.addEdge(s, t);
        }
        log.warn("[GraphCache] graph exceeds maxNodes={}, sampled {}/{} nodes", maxNodes, keep, total);
        return new Cached(version, b.build(), true, total);
    }

    private SnapshotView view(Cached c) {
        return new SnapshotView(c.snapshot, c.version, c.sampled, c.totalNodes);
    }

    /** 快照视图 / Snapshot view with metadata. */
    public record SnapshotView(GraphSnapshot snapshot, long version, boolean sampled, int totalNodes) {}

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
