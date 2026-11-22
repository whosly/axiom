package com.dataagent.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 紧凑图快照 / Compact in-memory graph snapshot.
 *
 * <p>把字符串节点 ID 映射为 int 索引，邻接关系用 int[] 数组存储。相比
 * {@code Map<String,List<String>>} 节省 5–10 倍内存，且遍历更快，适合万级节点。
 * Maps string node IDs to int indices and stores adjacency as int arrays, using 5–10x less
 * memory than Map<String,List<String>> and iterating faster for 10k+ node graphs.
 *
 * <p>快照是不可变构建结果，构建方一次性填好数据后发布；读取方只读，线程安全。
 * Immutable once built; the builder fills data then publishes it for concurrent readers.
 */
public final class GraphSnapshot {

    /** 节点总数 / Node count. */
    public final int size;
    /** 索引 → 原始 ID / Index → original node ID. */
    public final String[] ids;
    /** 索引 → 类型（knowledge/field/tag/…）/ Index → node type. */
    public final String[] types;
    /** 索引 → 展示名 / Index → display label. */
    public final String[] labels;
    /** 索引 → 邻居索引数组（无向化）/ Index → neighbor indices (undirected). */
    public final int[][] adjacency;
    /** 索引 → 度数 / Index → degree. */
    public final int[] degree;
    /** 原始 ID → 索引 / Original ID → index. */
    private final Map<String, Integer> index;

    private GraphSnapshot(String[] ids, String[] types, String[] labels, int[][] adjacency) {
        this.ids = ids;
        this.types = types;
        this.labels = labels;
        this.adjacency = adjacency;
        this.size = ids.length;
        this.degree = new int[size];
        this.index = new HashMap<>((int) (size / 0.75f) + 1);
        for (int i = 0; i < size; i++) {
            index.put(ids[i], i);
            degree[i] = adjacency[i].length;
        }
    }

    /**
     * 按 ID 查索引，不存在返回 -1 / Look up index by ID; -1 if absent.
     *
     * @param id 原始节点 ID / Original node ID.
     * @return 索引或 -1 / Index or -1
     */
    public int indexOf(String id) {
        Integer i = index.get(id);
        return i == null ? -1 : i;
    }

    /**
     * 流式构建器：两遍扫描，不重复保存原始行 / Streaming builder without retaining raw rows.
     */
    public static class Builder {
        private final Map<String, Integer> idx = new HashMap<>();
        private final List<String> ids = new ArrayList<>();
        private final List<String> types = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private final List<int[]> edges = new ArrayList<>();

        /**
         * 添加一个节点 / Add a node.
         *
         * @return 节点索引 / Node index
         */
        public int addNode(String id, String type, String label) {
            Integer existing = idx.get(id);
            if (existing != null) return existing;
            int i = ids.size();
            idx.put(id, i);
            ids.add(id);
            types.add(type);
            labels.add(label);
            return i;
        }

        /** 添加一条无向边（两端自动建节点）/ Add an undirected edge (auto-creating endpoints). */
        public void addEdge(String source, String target) {
            int s = addNode(source, "", source);
            int t = addNode(target, "", target);
            if (s != t) edges.add(new int[]{s, t});
        }

        /** 构建不可变快照（边转换为邻接数组）/ Build the immutable snapshot. */
        public GraphSnapshot build() {
            int n = ids.size();
            int[] counts = new int[n];
            for (int[] e : edges) { counts[e[0]]++; counts[e[1]]++; }
            int[][] adj = new int[n][];
            for (int i = 0; i < n; i++) adj[i] = new int[counts[i]];
            int[] pos = new int[n];
            for (int[] e : edges) {
                int s = e[0], t = e[1];
                adj[s][pos[s]++] = t;
                adj[t][pos[t]++] = s;
            }
            return new GraphSnapshot(ids.toArray(new String[0]), types.toArray(new String[0]),
                    labels.toArray(new String[0]), adj);
        }
    }
}
