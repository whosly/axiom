package com.dataagent.service;

import com.dataagent.domain.KnowledgeChangedEvent;
import com.dataagent.model.KnowledgeEntry;
import com.dataagent.repository.KnowledgeGraphRepository;
import com.dataagent.repository.KnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识图谱增量同步测试 / Tests for incremental knowledge-graph sync.
 *
 * <p>验证所有变更类型都按 ID 增量处理，不触发 clear() 全量重建。
 * Verifies all change types sync incrementally by ID without a full clear() rebuild.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphSyncTest {

    @Mock private KnowledgeGraphRepository graphRepo;
    @Mock private KnowledgeRepository knowledgeRepo;

    private KnowledgeGraphService service;

    /** 构造被测服务 / Build the service under test. */
    @BeforeEach
    void setUp() {
        service = new KnowledgeGraphService(graphRepo, knowledgeRepo);
    }

    /** 构造一条知识 / Build a knowledge entry. */
    private KnowledgeEntry entry(String id) {
        return KnowledgeEntry.builder().id(id).kind("metric").title("GMV")
                .fields(List.of("销售额")).tags(List.of("销售")).build();
    }

    /** 单条 ADD：删旧子图后重建，不清空全表 / Single ADD: remove old subgraph then rebuild, no full clear. */
    @Test
    @DisplayName("ADD 单条增量，不触发全量清空")
    void addIsIncremental() {
        when(knowledgeRepo.findById("e1")).thenReturn(entry("e1"));
        service.onKnowledgeChanged(new KnowledgeChangedEvent(this, KnowledgeChangedEvent.ChangeType.ADD, "e1"));

        verify(graphRepo).deleteEntrySubgraph("e1");
        verify(graphRepo).addNode(org.mockito.ArgumentMatchers.eq("knowledge:e1"),
                org.mockito.ArgumentMatchers.eq("knowledge"), org.mockito.ArgumentMatchers.any());
        verify(graphRepo).deleteOrphanNodes();
        verify(graphRepo, never()).clear();
    }

    /** BATCH：按每个 ID 增量，不触发全量重建 / BATCH: incremental per ID, no full rebuild. */
    @Test
    @DisplayName("BATCH 按 ID 列表增量，不触发全量清空")
    void batchIsIncremental() {
        when(knowledgeRepo.findById("e1")).thenReturn(entry("e1"));
        when(knowledgeRepo.findById("e2")).thenReturn(entry("e2"));
        service.onKnowledgeChanged(new KnowledgeChangedEvent(this, KnowledgeChangedEvent.ChangeType.BATCH,
                List.of("e1", "e2")));

        verify(graphRepo).deleteEntrySubgraph("e1");
        verify(graphRepo).deleteEntrySubgraph("e2");
        verify(graphRepo, times(2)).addNode(org.mockito.ArgumentMatchers.startsWith("knowledge:"),
                org.mockito.ArgumentMatchers.eq("knowledge"), org.mockito.ArgumentMatchers.any());
        verify(graphRepo).deleteOrphanNodes();
        verify(graphRepo, never()).clear();
    }

    /** DELETE_BATCH：仅删子图，不重新写入 / DELETE_BATCH: only remove subgraphs, no re-write. */
    @Test
    @DisplayName("DELETE_BATCH 仅删子图不重建")
    void deleteBatchOnlyRemoves() {
        service.onKnowledgeChanged(new KnowledgeChangedEvent(this, KnowledgeChangedEvent.ChangeType.DELETE_BATCH,
                List.of("e1", "e2")));

        verify(graphRepo).deleteEntrySubgraph("e1");
        verify(graphRepo).deleteEntrySubgraph("e2");
        verify(graphRepo, never()).addNode(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(graphRepo).deleteOrphanNodes();
        verify(graphRepo, never()).clear();
    }

    /** 空 ID 列表跳过，不做任何操作 / Empty ID list is a no-op. */
    @Test
    @DisplayName("空变更事件跳过")
    void emptyEventIsNoOp() {
        service.onKnowledgeChanged(new KnowledgeChangedEvent(this, KnowledgeChangedEvent.ChangeType.BATCH,
                List.of()));
        verify(graphRepo, never()).deleteEntrySubgraph(org.mockito.ArgumentMatchers.anyString());
        verify(graphRepo, never()).clear();
    }
}
