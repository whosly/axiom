package com.dataagent.service;

import com.dataagent.graph.GraphSnapshot;
import com.dataagent.graph.GraphSnapshotCache;
import com.dataagent.graph.GraphSnapshotCache.SnapshotView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 知识图谱推理测试 / Tests for knowledge-graph reasoning on compact snapshots.
 *
 * <p>用固定小图验证社区发现、最短路径与关联分析在新 int 索引结构上的正确性。
 * Verifies algorithms on the new int-indexed snapshot with a fixed small graph.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphReasonerTest {

    @Mock private GraphSnapshotCache cache;
    private KnowledgeGraphReasoner reasoner;

    /** 构造图：k1/k2 通过 field:sales 相连，k3 孤立 / Graph: k1/k2 via field:sales, k3 isolated. */
    @BeforeEach
    void setUp() {
        GraphSnapshot.Builder b = new GraphSnapshot.Builder();
        b.addNode("knowledge:k1", "knowledge", "GMV");
        b.addNode("knowledge:k2", "knowledge", "客单价");
        b.addNode("knowledge:k3", "knowledge", "孤立知识");
        b.addNode("field:sales", "field", "销售额");
        b.addEdge("knowledge:k1", "field:sales");
        b.addEdge("knowledge:k2", "field:sales");
        GraphSnapshot snapshot = b.build();
        when(cache.get()).thenReturn(new SnapshotView(snapshot, 1L, false, 4));
        reasoner = new KnowledgeGraphReasoner(cache);
    }

    /** 社区发现：相连节点聚为一类，含采样元数据 / Connected nodes grouped; sampling metadata present. */
    @Test
    @DisplayName("社区发现")
    void communities() {
        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) reasoner.communities(5).get("communities");
        assertThat(result).isNotEmpty();
        assertThat(result).anySatisfy(c -> assertThat((int) c.get("size")).isGreaterThanOrEqualTo(2));
        Map<String, Object> whole = reasoner.communities(5);
        assertThat(whole).containsEntry("sampled", false);
        assertThat(whole).containsEntry("totalNodes", 4);
        assertThat(whole).containsEntry("version", 1L);
    }

    /** 最短路径：k1 与 k2 经 field:sales 两跳可达 / Shortest path via shared field. */
    @Test
    @DisplayName("最短路径可达")
    void shortestPath() {
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) reasoner.shortestPath("knowledge:k1", "knowledge:k2");
        assertThat(result).containsEntry("reachable", true);
        assertThat(result).containsEntry("length", 2);
        @SuppressWarnings("unchecked")
        var path = (List<Map<String, String>>) result.get("path");
        assertThat(path).hasSize(3);
    }

    /** 不存在的节点不可达 / Unreachable for unknown node. */
    @Test
    @DisplayName("不可达节点")
    void unreachable() {
        var result = reasoner.shortestPath("knowledge:k1", "knowledge:nonexistent");
        assertThat(result).containsEntry("reachable", false);
    }

    /** 关联分析：k1 与 k2 共享 field:sales / Related via shared field. */
    @Test
    @DisplayName("关联分析")
    void related() {
        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) reasoner.related("knowledge:k1", 10).get("related");
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("id", "knowledge:k2");
        assertThat(result.get(0)).containsEntry("sharedRelations", 1);
    }

    /** 不存在的条目无关联 / Unknown entry has no relations. */
    @Test
    @DisplayName("无关联条目")
    void noRelated() {
        var result = reasoner.related("knowledge:nonexistent", 10);
        assertThat(result).containsEntry("count", 0);
    }
}
