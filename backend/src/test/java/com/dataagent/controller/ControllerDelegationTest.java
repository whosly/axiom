package com.dataagent.controller;

import com.dataagent.model.KnowledgeEntry;
import com.dataagent.service.DocumentIngestionService;
import com.dataagent.service.KnowledgeGraphReasoner;
import com.dataagent.service.KnowledgeGraphService;
import com.dataagent.service.KnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller 委托测试 / Controller delegation tests.
 *
 * <p>验证简单 Controller 端点正确把调用委托给 Service 并返回结果结构。
 * Verifies that simple controller endpoints delegate to services and return expected shapes.
 */
@ExtendWith(MockitoExtension.class)
class ControllerDelegationTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private KnowledgeGraphReasoner reasoner;
    @Mock private KnowledgeService knowledgeService;
    @Mock private DocumentIngestionService documentIngestion;

    /** 图谱 Controller 委托 / Graph controller delegates. */
    @Test
    @DisplayName("知识图谱 Controller 委托")
    void graphController() {
        when(graphService.graph()).thenReturn(Map.of("nodes", List.of()));
        when(graphService.neighbors("n1", 2)).thenReturn(Map.of("nodes", List.of()));
        when(graphService.stats()).thenReturn(Map.of("nodes", 0));

        KnowledgeGraphController c = new KnowledgeGraphController(graphService);
        assertThat(c.rebuild()).isNotNull();
        assertThat(c.graph()).containsKey("nodes");
        assertThat(c.nodes()).isNotNull();
        assertThat(c.edges()).isNotNull();
        assertThat(c.neighbors("n1", 2)).containsKey("nodes");
        assertThat(c.stats()).containsKey("nodes");
        verify(graphService).rebuild();
        verify(graphService).neighbors("n1", 2);
    }

    /** 推理 Controller 委托 / Reasoning controller delegates. */
    @Test
    @DisplayName("图谱推理 Controller 委托")
    void reasonerController() {
        when(reasoner.communities(20)).thenReturn(Map.of("count", 0));
        when(reasoner.shortestPath("a", "b")).thenReturn(Map.of("reachable", false));
        when(reasoner.related("e1", 10)).thenReturn(Map.of("count", 0));

        KnowledgeGraphReasonerController c = new KnowledgeGraphReasonerController(reasoner);
        assertThat(c.communities(20)).containsKey("count");
        assertThat(c.path("a", "b")).containsEntry("reachable", false);
        assertThat(c.related("e1", 10)).containsKey("count");
        verify(reasoner).communities(20);
        verify(reasoner).shortestPath("a", "b");
    }

    /** 知识 Controller 列表/状态/reindex 委托 / Knowledge controller delegates. */
    @Test
    @DisplayName("知识 Controller 委托")
    void knowledgeController() {
        when(knowledgeService.listAll()).thenReturn(List.of());
        when(knowledgeService.stats()).thenReturn(List.of());
        when(knowledgeService.statusStats()).thenReturn(List.of());
        when(knowledgeService.reindex(false)).thenReturn(Map.of("total", 0));
        when(knowledgeService.listSources()).thenReturn(List.of());

        KnowledgeController c = new KnowledgeController(knowledgeService, documentIngestion);
        assertThat(c.list()).containsKey("entries");
        assertThat(c.listByKind("metric")).isNotNull();
        assertThat(c.status()).isNotNull();
        assertThat(c.sources()).isNotNull();
        assertThat(c.reindex(false)).containsEntry("total", 0);
        verify(knowledgeService).reindex(false);
        verify(knowledgeService).listSources();
    }

    /** 知识 Controller 删除/恢复委托 / Knowledge controller delete/restore delegates. */
    @Test
    @DisplayName("知识 Controller 删除与恢复")
    void knowledgeMutation() {
        when(knowledgeService.restore("e1")).thenReturn(true);
        when(knowledgeService.deleteBySource("src1")).thenReturn(2);

        KnowledgeController c = new KnowledgeController(knowledgeService, documentIngestion);
        assertThat(c.restore("e1")).containsKey("success");
        assertThat(c.deleteBySource("src1")).containsKey("deleted");
        c.delete("e1");
        verify(knowledgeService).delete("e1");
        verify(knowledgeService).restore("e1");
        verify(knowledgeService).deleteBySource("src1");
    }
}
