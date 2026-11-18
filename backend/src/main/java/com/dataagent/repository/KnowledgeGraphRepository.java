package com.dataagent.repository;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
        log.info("[KnowledgeGraph] schema ready");
    }

    /** 清空图（重建前）/ Clear graph (before rebuild). */
    public void clear() {
        jdbc.update("DELETE FROM kg_edges");
        jdbc.update("DELETE FROM kg_nodes");
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
    public Set<String> neighbors(String startId, int hops) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(startId);
        List<String> frontier = new ArrayList<>();
        frontier.add(startId);
        for (int h = 0; h < Math.max(0, hops); h++) {
            List<String> next = new ArrayList<>();
            for (String node : frontier) {
                List<String> outs = jdbc.queryForList(
                        "SELECT target FROM kg_edges WHERE source = ?", String.class, node);
                List<String> ins = jdbc.queryForList(
                        "SELECT source FROM kg_edges WHERE target = ?", String.class, node);
                for (String n : outs) if (visited.add(n)) next.add(n);
                for (String n : ins) if (visited.add(n)) next.add(n);
            }
            frontier = next;
            if (frontier.isEmpty()) break;
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
