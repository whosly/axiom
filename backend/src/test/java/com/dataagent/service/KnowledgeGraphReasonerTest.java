package com.dataagent.service;

import com.dataagent.repository.KnowledgeGraphRepository;
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
 * 知识图谱推理测试 / Tests for knowledge-graph reasoning algorithms.
 *
 * <p>用固定的小型图验证社区发现、最短路径与关联分析的正确性。
 * Verifies community detection, shortest path and related analysis on a fixed small graph.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphReasonerTest {

    @Mock private KnowledgeGraphRepository graphRepo;
    private KnowledgeGraphReasoner reasoner;

    /** 构造图：k1/k2 通过 field:sales 相连 / Graph: k1/k2 linked via field:sales. */
    @BeforeEach
    void setUp() {
        reasoner = new KnowledgeGraphReasoner(graphRepo);
        when(graphRepo.listNodes()).thenReturn(List.of(
                Map.of("id", "knowledge:k1", "type", "knowledge", "label", "GMV"),
                Map.of("id", "knowledge:k2", "type", "knowledge", "label", "客单价"),
                Map.of("id", "knowledge:k3", "type", "knowledge", "label", "孤立知识"),
                Map.of("id", "field:sales", "type", "field", "label", "销售额")));
        when(graphRepo.listEdges()).thenReturn(List.of(
                Map.of("source", "knowledge:k1", "target", "field:sales", "relation", "references"),
                Map.of("source", "knowledge:k2", "target", "field:sales", "relation", "references")));
    }

    /** 社区发现：相连节点聚为一类 / Community detection groups connected nodes. */
    @Test
    @DisplayName("社区发现")
    void communities() {
        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) reasoner.communities(5).get("communities");
        assertThat(result).isNotEmpty();
        assertThat(result).anySatisfy(c -> assertThat((int) c.get("size")).isGreaterThanOrEqualTo(2));
        assertThat(reasoner.communities(5)).containsKey("count");
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
