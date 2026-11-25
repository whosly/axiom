package com.axiom.service;

import com.axiom.repository.KnowledgeGraphRepository;
import com.axiom.repository.KnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邻居查询子图下推测试 / Tests for neighbor-query subgraph push-down.
 *
 * <p>验证 neighbors() 走 nodesByIds / edgesWithinSubgraph 分批下推路径，
 * 不再全表拉取（图大时 O(全图)→O(子图)），且 hops 钳制语义不变。
 * / neighbors() must use the chunked push-down methods instead of full-table
 * loads, with unchanged hops clamping semantics.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphNeighborsTest {

    @Mock private KnowledgeGraphRepository graphRepo;
    @Mock private KnowledgeRepository knowledgeRepo;

    private KnowledgeGraphService service;

    /** 构造被测服务 / Build the service under test. */
    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm =
                org.mockito.Mockito.mock(PlatformTransactionManager.class);
        org.mockito.Mockito.lenient()
                .when(tm.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        service = new KnowledgeGraphService(graphRepo, knowledgeRepo, new TransactionTemplate(tm));
    }

    /** 走下推方法取子图，不再调 listNodes / listEdges / Uses push-down methods, never full-table loads. */
    @Test
    @DisplayName("neighbors 委托 nodesByIds 与 edgesWithinSubgraph，不触全表")
    void neighborsUsesSqlPushdown() {
        Set<String> ids = new LinkedHashSet<>(List.of("n1", "n2"));
        when(graphRepo.neighbors("n1", 2)).thenReturn(ids);
        when(graphRepo.nodesByIds(ids))
                .thenReturn(List.of(Map.of("id", "n1"), Map.of("id", "n2")));
        when(graphRepo.edgesWithinSubgraph(ids))
                .thenReturn(List.of(Map.of("source", "n1", "target", "n2")));

        Map<String, Object> result = service.neighbors("n1", 2);

        assertThat(result.get("center")).isEqualTo("n1");
        assertThat(result.get("hops")).isEqualTo(2);
        assertThat((List<?>) result.get("nodes")).hasSize(2);
        assertThat((List<?>) result.get("edges")).hasSize(1);
        verify(graphRepo).nodesByIds(ids);
        verify(graphRepo).edgesWithinSubgraph(ids);
        verify(graphRepo, never()).listNodes();
        verify(graphRepo, never()).listEdges();
    }

    /** hops 仍然钳制到 [1, 5]，且回传实际值 / Hops still clamped to [1, 5] and echoed back. */
    @Test
    @DisplayName("hops 超限钳制为 5")
    void hopsClampedToFive() {
        when(graphRepo.neighbors("n1", 5)).thenReturn(new LinkedHashSet<>(List.of("n1")));
        when(graphRepo.nodesByIds(any())).thenReturn(List.of());
        when(graphRepo.edgesWithinSubgraph(any())).thenReturn(List.of());

        Map<String, Object> result = service.neighbors("n1", 10);

        assertThat(result.get("hops")).isEqualTo(5);
        verify(graphRepo).neighbors("n1", 5);
    }
}
