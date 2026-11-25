package com.axiom.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图谱仓储子图下推查询测试 / Tests for graph repository subgraph push-down queries.
 *
 * <p>{@code nodesByIds / edgesWithinSubgraph} 的核心风险在 IN 分批边界：
 * 用真实 H2 内存库验证跨批 (source, target) 组合不漏边、排序与 listNodes 一致。
 * / The real risk of nodesByIds/edgesWithinSubgraph lies at IN-chunk boundaries:
 * verified against a real in-memory H2 so cross-chunk (source, target) pairs
 * cannot be missed and ordering matches listNodes.
 */
class KnowledgeGraphRepositoryTest {

    private KnowledgeGraphRepository repo;
    private JdbcTemplate jdbc;

    /** 每个用例独立建表并清空 / Fresh tables, cleared per test. */
    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:kgrepo;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        repo = new KnowledgeGraphRepository(jdbc);
        repo.init();
        repo.clear();
    }

    /** 大小写无关读取行值 / Case-insensitive row value lookup. */
    private static Object value(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /** 只返回命中行，且顺序与 listNodes 一致（type, id 升序）/ Only matched rows, ordered like listNodes. */
    @Test
    @DisplayName("nodesByIds 只返回命中行且按 (type, id) 排序")
    void nodesByIdsReturnsOnlyMatchedRowsInListNodesOrder() {
        repo.addNode("b", "field", "字段B");
        repo.addNode("z", "knowledge", "知识Z");
        repo.addNode("a", "knowledge", "知识A");
        repo.addNode("outside", "field", "不在集合内");

        List<Map<String, Object>> rows = repo.nodesByIds(new LinkedHashSet<>(List.of("z", "a", "b")));

        // field < knowledge（type 升序），同 type 内按 id 升序
        // / "field" sorts before "knowledge", then by id ascending.
        assertThat(rows).extracting(r -> String.valueOf(value(r, "id")))
                .containsExactly("b", "a", "z");
        assertThat(rows).extracting(r -> String.valueOf(value(r, "type")))
                .containsExactly("field", "knowledge", "knowledge");
    }

    /**
     * 跨 IN 分批的边不漏：source 落在第 1 批、target 落在第 2 批，
     * 「source IN 分批 + target Java 过滤」策略必须覆盖这种组合。
     * / Cross-chunk edge: source in chunk 1, target in chunk 2 must not be missed.
     */
    @Test
    @DisplayName("450 节点跨 IN 分批：节点全取回、跨批边不漏、外部端点被滤除")
    void chunkedQueriesCoverCrossChunkEdges() {
        int n = 450; // > IN_CHUNK(400)，强制产生 2 个分批 / forces 2 chunks
        List<Object[]> nodeArgs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodeArgs.add(new Object[]{"n" + i, "knowledge", "节点" + i});
            ids.add("n" + i);
        }
        jdbc.batchUpdate("INSERT INTO kg_nodes (id, type, label) VALUES (?,?,?)", nodeArgs);

        Set<String> idSet = new LinkedHashSet<>(ids);
        // 跨批边 / cross-chunk edge: n0 在第 1 批，n449 在第 2 批
        repo.addEdge("n0", "n449", "relates");
        // 两端都在子图内、同批 / both endpoints inside, same chunk
        repo.addEdge("n1", "n2", "relates");
        // 一端在集合外 / one endpoint outside
        repo.addEdge("n2", "outside", "relates");
        // source 在外（source IN 不会命中） / source outside (never matched by source IN)
        repo.addEdge("outside", "n2", "relates");

        List<Map<String, Object>> nodes = repo.nodesByIds(idSet);
        assertThat(nodes).hasSize(n);
        assertThat(nodes).extracting(r -> String.valueOf(value(r, "id")))
                .contains("n0", "n449");

        List<Map<String, Object>> edges = repo.edgesWithinSubgraph(idSet);
        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(
                        e -> String.valueOf(value(e, "source")) + "->" + String.valueOf(value(e, "target")))
                .containsExactlyInAnyOrder("n0->n449", "n1->n2");
    }

    /** 空 ID 集合返回空结果，不发 SQL / Empty ID set short-circuits to an empty result. */
    @Test
    @DisplayName("空集合返回空结果")
    void emptyIdSetReturnsEmpty() {
        repo.addNode("a", "knowledge", "知识A");
        assertThat(repo.nodesByIds(Set.of())).isEmpty();
        assertThat(repo.edgesWithinSubgraph(Set.of())).isEmpty();
    }
}
