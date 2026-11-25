package com.axiom.repository;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱存储 / Knowledge graph repository.
 *
 * <p>用两张表在 H2/SQLite 中存储图结构：节点（实体）与边（关系）。
 * 相比引入 Neo4j，这种轻量方案对嵌入式部署更友好，且足够表达指标-字段-数据集的关联。
 * Stores graph structure in two tables (nodes and edges) in H2/SQLite. Compared to
 * Neo4j, this lightweight approach is friendlier to embedded deployment and sufficient
 * for metric-field-dataset associations.
 */
@Slf4j
@Repository
public class KnowledgeGraphRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeGraphRepository(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 初始化图表 / Initialize graph tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS kg_nodes (
                id VARCHAR(128) PRIMARY KEY,
                type VARCHAR(32) NOT NULL,
                label VARCHAR(255)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS kg_edges (
                source VARCHAR(128) NOT NULL,
                target VARCHAR(128) NOT NULL,
                relation VARCHAR(32) NOT NULL,
                PRIMARY KEY (source, target, relation)
            )
        """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kg_edge_source ON kg_edges(source)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_kg_edge_target ON kg_edges(target)");
        // 图版本号：每次结构变更递增，供推理层缓存失效 / Graph version for cache invalidation.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS kg_meta (
                meta_key VARCHAR(32) PRIMARY KEY,
                meta_value VARCHAR(64) NOT NULL
            )
            """);
        // H2/SQLite 通用：不存在才插入 / Portable across H2 and SQLite.
        jdbc.update("INSERT INTO kg_meta (meta_key, meta_value) SELECT 'version', '0' WHERE NOT EXISTS (SELECT 1 FROM kg_meta WHERE meta_key = 'version')");
        log.info("[KnowledgeGraph] schema ready");
    }

    /**
     * 当前图版本号（结构变更时递增）/ Current graph version (bumped on structural changes).
     *
     * @return 版本号 / Version number.
     */
    public long version() {
        String v = jdbc.queryForObject("SELECT meta_value FROM kg_meta WHERE meta_key = 'version'", String.class);
        try { return v == null ? 0 : Long.parseLong(v); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * 递增图版本号：由 Service 在一次逻辑写操作完成后调用一次，避免逐条写重复递增。
     * Bump version once per logical write operation, called by the service after a batch of writes.
     *
     * <p>必须是单条原子 SQL：读-改-写两步在并发下会把两个递增合并成一个，
     * 图已变但版本未变，快照缓存会长期返回旧结构。/ Must be a single atomic statement:
     * a read-modify-write merges concurrent bumps, leaving the cache serving stale snapshots.
     */
    public void bumpVersion() {
        jdbc.update("UPDATE kg_meta SET meta_value = CAST(CAST(meta_value AS BIGINT) + 1 AS VARCHAR) WHERE meta_key = 'version'");
    }

    /** 清空图（重建前）/ Clear graph (before rebuild). */
    public void clear() {
        jdbc.update("DELETE FROM kg_edges");
        jdbc.update("DELETE FROM kg_nodes");
    }

    /**
     * 删除某条知识的子图（增量更新用）：删除 knowledge:{id} 节点及其全部出边。
     * Delete one entry's subgraph for incremental updates: its node and outgoing edges.
     *
     * @param entryId 知识条目 ID / Knowledge entry ID.
     */
    public void deleteEntrySubgraph(String entryId) {
        String kid = "knowledge:" + entryId;
        jdbc.update("DELETE FROM kg_edges WHERE source = ?", kid);
        jdbc.update("DELETE FROM kg_nodes WHERE id = ?", kid);
    }

    /**
     * 清理孤立节点：删除没有任何边相连的 field/tag/dataset/intent 节点，
     * 保留 knowledge 节点（即使暂时无边）。/ Remove orphan non-knowledge nodes.
     */
    public void deleteOrphanNodes() {
        jdbc.update("""
            DELETE FROM kg_nodes
            WHERE type <> 'knowledge'
              AND id NOT IN (SELECT source FROM kg_edges)
              AND id NOT IN (SELECT target FROM kg_edges)
            """);
    }

    /** 增加一个节点 / Add a node. */
    public void addNode(String id, String type, String label) {
        jdbc.update("DELETE FROM kg_nodes WHERE id = ?", id);
        jdbc.update("INSERT INTO kg_nodes (id, type, label) VALUES (?,?,?)", id, type, label);
    }

    /** 增加一条边 / Add an edge. */
    public void addEdge(String source, String target, String relation) {
        // 幂等：先删后插 / Idempotent: delete then insert.
        jdbc.update("DELETE FROM kg_edges WHERE source=? AND target=? AND relation=?", source, target, relation);
        jdbc.update("INSERT INTO kg_edges (source, target, relation) VALUES (?,?,?)", source, target, relation);
    }

    /** 列出所有节点 / List all nodes. */
    public List<Map<String, Object>> listNodes() {
        return jdbc.queryForList("SELECT id, type, label FROM kg_nodes ORDER BY type, id");
    }

    /**
     * 按主键批量取节点（IN 分批，防 SQLite 999 绑定上限）。
     * Fetch nodes by primary key with chunked IN clauses.
     *
     * <p>返回顺序与 {@link #listNodes()} 一致（type, id 升序）：分批查询无法全局排序，
     * 收集后在 Java 侧补齐，调用方无感知切换。/ Result order matches listNodes()
     * (type, id ascending); chunked queries cannot sort globally, so rows are
     * re-sorted in Java after collection.
     *
     * @param ids 节点 ID 集合 / Node ID set.
     * @return 命中的节点行 / Matched node rows.
     */
    public List<Map<String, Object>> nodesByIds(Collection<String> ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> idList = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int from = 0; from < idList.size(); from += IN_CHUNK) {
            List<String> chunk = idList.subList(from, Math.min(from + IN_CHUNK, idList.size()));
            out.addAll(jdbc.queryForList(
                    "SELECT id, type, label FROM kg_nodes WHERE id IN (" + placeholders(chunk.size()) + ")",
                    chunk.toArray()));
        }
        out.sort(Comparator
                .comparing((Map<String, Object> r) -> String.valueOf(value(r, "type")))
                .thenComparing(r -> String.valueOf(value(r, "id"))));
        return out;
    }

    /**
     * 取子图内部边：两端都在给定 ID 集合内的边。
     * Fetch edges whose both endpoints fall inside the given ID set.
     *
     * <p>实现取巧在正确性：用「source IN 分批」查询（命中 idx_kg_edge_source），
     * target 在 Java 侧过滤。结果边的 source 必然在集合内，因此每个分批都能覆盖到，
     * 跨分批不会漏边；若直接写「source IN AND target IN」双 IN，同一分批内无法
     * 覆盖跨批的 (source, target) 组合，会漏边。/ The trick is correctness: query
     * with chunked "source IN" (hits idx_kg_edge_source) and filter target in Java.
     * Every result edge's source is guaranteed to be in the set, so no chunk can miss
     * it; a dual "source IN AND target IN" would miss edges spanning two chunks.
     *
     * @param ids 子图节点 ID 集合 / Subgraph node ID set.
     * @return 子图内部边 / Edges internal to the subgraph.
     */
    public List<Map<String, Object>> edgesWithinSubgraph(Collection<String> ids) {
        Set<String> idSet = (ids instanceof Set<String> s) ? s : new LinkedHashSet<>(ids);
        List<String> idList = new ArrayList<>(idSet);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int from = 0; from < idList.size(); from += IN_CHUNK) {
            List<String> chunk = idList.subList(from, Math.min(from + IN_CHUNK, idList.size()));
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT source, target, relation FROM kg_edges WHERE source IN (" + placeholders(chunk.size()) + ")",
                    chunk.toArray());
            for (Map<String, Object> row : rows) {
                if (idSet.contains(String.valueOf(value(row, "source")))
                        && idSet.contains(String.valueOf(value(row, "target")))) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** IN 占位符串 / Comma-separated IN placeholders. */
    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    /**
     * 大小写不敏感读取行值 / Case-insensitive row value lookup.
     *
     * <p>JdbcTemplate.queryForList 在 H2 下返回大写列名，SQLite 下返回小写列名；
     * 统一用此方法读取，保证两种元数据模式下行为一致。
     * / JdbcTemplate returns uppercase column keys on H2 and lowercase on SQLite;
     * this helper normalizes access across both metadata modes.
     */
    private static Object value(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /** 列出所有边 / List all edges. */
    public List<Map<String, Object>> listEdges() {
        return jdbc.queryForList("SELECT source, target, relation FROM kg_edges");
    }

    /**
     * BFS 查询 N 跳邻居 / BFS query for N-hop neighbors.
     *
     * @param startId 起始节点 / Start node.
     * @param hops    跳数 / Number of hops.
     * @return 可达节点 ID 集合 / Set of reachable node IDs.
     */
    /** IN 子句单批参数上限：SQLite 绑定参数上限 999，分批防超限 / IN-clause chunk cap (SQLite binds max 999 params). */
    private static final int IN_CHUNK = 400;

    public Set<String> neighbors(String startId, int hops) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(startId);
        List<String> frontier = new ArrayList<>();
        frontier.add(startId);
        for (int h = 0; h < Math.max(0, hops); h++) {
            if (frontier.isEmpty()) break;
            // 整层一次批量查询替代逐节点 2 条 SQL 的 N+1 模式；
            // IN 参数按 400 分批，避免 SQLite 的 999 绑定上限。
            // / One batched query per BFS level instead of 2 SQLs per node (N+1);
            // IN args chunked by 400 to stay under SQLite's 999-bind limit.
            Set<String> next = new LinkedHashSet<>();
            for (int from = 0; from < frontier.size(); from += IN_CHUNK) {
                List<String> chunk = frontier.subList(from, Math.min(from + IN_CHUNK, frontier.size()));
                Object[] args = new Object[chunk.size() * 2];
                System.arraycopy(chunk.toArray(), 0, args, 0, chunk.size());
                System.arraycopy(chunk.toArray(), 0, args, chunk.size(), chunk.size());
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT source, target FROM kg_edges WHERE source IN (" + placeholders(chunk.size()) + ")"
                                + " OR target IN (" + placeholders(chunk.size()) + ")", args);
                for (Map<String, Object> row : rows) {
                    String src = String.valueOf(row.get("source"));
                    String tgt = String.valueOf(row.get("target"));
                    if (src.equals(tgt)) continue;
                    // 只把「已访问一侧的另一端」加入下一层，避免同一层的边互拉
                    // / Only pull the far end of an edge whose near end is already visited.
                    if (visited.contains(src) && visited.add(tgt)) next.add(tgt);
                    else if (visited.contains(tgt) && visited.add(src)) next.add(src);
                }
            }
            frontier = new ArrayList<>(next);
        }
        return visited;
    }

    /** 图统计 / Graph statistics. */
    public Map<String, Object> stats() {
        Map<String, Object> m = new HashMap<>();
        m.put("nodes", jdbc.queryForObject("SELECT COUNT(*) FROM kg_nodes", Integer.class));
        m.put("edges", jdbc.queryForObject("SELECT COUNT(*) FROM kg_edges", Integer.class));
        m.put("byType", jdbc.queryForList(
                "SELECT type, COUNT(*) AS count FROM kg_nodes GROUP BY type ORDER BY count DESC"));
        return m;
    }
}
