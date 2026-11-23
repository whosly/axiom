package com.axiom.graph;

import com.axiom.graph.GraphSnapshotCache.SnapshotView;
import com.axiom.repository.KnowledgeGraphRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图快照缓存测试 / Tests for the versioned graph snapshot cache.
 *
 * <p>验证版本命中零重载、版本变更重建、以及大图按度数采样。
 * Verifies version-hit reuse, version-change rebuild and top-degree sampling for large graphs.
 */
@ExtendWith(MockitoExtension.class)
class GraphSnapshotCacheTest {

    @Mock private KnowledgeGraphRepository repo;

    private GraphSnapshotCache cache() {
        GraphSnapshotCache c = new GraphSnapshotCache(repo);
        ReflectionTestUtils.setField(c, "maxNodes", 4);
        ReflectionTestUtils.setField(c, "sampleSize", 3);
        return c;
    }

    private Map<String, Object> node(String id, String type) {
        return Map.of("id", id, "type", type, "label", id);
    }

    private Map<String, Object> edge(String s, String t) {
        return Map.of("source", s, "target", t, "relation", "references");
    }

    /** 版本命中时复用快照，不重复读库 / Same version reuses snapshot without reloading. */
    @Test
    @DisplayName("版本命中复用快照")
    void versionHitReuses() {
        when(repo.version()).thenReturn(1L).thenReturn(1L);
        when(repo.listNodes()).thenReturn(List.of(node("a", "knowledge"), node("b", "field")));
        when(repo.listEdges()).thenReturn(List.of(edge("a", "b")));
        GraphSnapshotCache c = cache();

        SnapshotView first = c.get();
        SnapshotView second = c.get();
        assertThat(first.snapshot()).isSameAs(second.snapshot());
        verify(repo, times(1)).listNodes();
    }

    /** 版本变化时重建快照 / Version change triggers rebuild. */
    @Test
    @DisplayName("版本变化重建")
    void versionChangeRebuilds() {
        when(repo.version()).thenReturn(1L, 2L);
        when(repo.listNodes()).thenReturn(List.of(node("a", "knowledge"), node("b", "field")));
        when(repo.listEdges()).thenReturn(List.of(edge("a", "b")));
        GraphSnapshotCache c = cache();

        SnapshotView first = c.get();
        SnapshotView second = c.get();
        assertThat(first.snapshot()).isNotSameAs(second.snapshot());
        assertThat(second.version()).isEqualTo(2L);
        verify(repo, times(2)).listNodes();
    }

    /** 大图按度数采样，标记 sampled 且限制节点数 / Large graph is sampled by degree with flag. */
    @Test
    @DisplayName("大图度数采样")
    void largeGraphSampling() {
        when(repo.version()).thenReturn(1L);
        List<Map<String, Object>> nodes = new ArrayList<>();
        // 5 个节点 > maxNodes=4；hub 度数最高 / 5 nodes above cap; hub has highest degree.
        for (int i = 0; i < 5; i++) nodes.add(node("n" + i, "knowledge"));
        when(repo.listNodes()).thenReturn(nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 1; i < 5; i++) edges.add(edge("n0", "n" + i));
        when(repo.listEdges()).thenReturn(edges);
        GraphSnapshotCache c = cache();

        SnapshotView view = c.get();
        assertThat(view.sampled()).isTrue();
        assertThat(view.totalNodes()).isEqualTo(5);
        assertThat(view.snapshot().size).isLessThanOrEqualTo(3);
        // 高度数 hub 必须保留 / High-degree hub must be retained.
        assertThat(view.snapshot().indexOf("n0")).isGreaterThanOrEqualTo(0);
    }

    /** 空图返回空快照 / Empty graph returns empty snapshot. */
    @Test
    @DisplayName("空图快照")
    void emptyGraph() {
        when(repo.version()).thenReturn(1L);
        when(repo.listNodes()).thenReturn(List.of());
        when(repo.listEdges()).thenReturn(List.of());
        SnapshotView view = cache().get();
        assertThat(view.snapshot().size).isZero();
        assertThat(view.sampled()).isFalse();
    }
}
