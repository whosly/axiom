package com.dataagent.service;

import com.dataagent.config.AppProperties;
import com.dataagent.domain.KnowledgeChangedEvent;
import com.dataagent.model.KnowledgeEntry;
import com.dataagent.model.KnowledgeRequest;
import com.dataagent.rerank.RerankService;
import com.dataagent.repository.KnowledgeRepository;
import com.dataagent.repository.KnowledgeSourceRepository;
import com.dataagent.repository.MetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库双写一致性单元测试 / Unit tests for knowledge dual-write consistency.
 *
 * <p>核心不变量：元数据必须先于向量写入，且向量失败时状态落为 vector_failed，
 * 保证元数据侧始终有可重建的记录，不产生孤儿向量。
 * Core invariant: metadata is written before vectors, and a vector failure leaves
 * a vector_failed record so no orphan vectors are created.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock private KnowledgeRepository repository;
    @Mock private KnowledgeVectorStore vectorStore;
    @Mock private MetadataRepository metadataRepository;
    @Mock private KnowledgeTokenizer tokenizer;
    @Mock private AppProperties props;
    @Mock private RerankService rerankService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private KnowledgeSourceRepository sourceRepository;

    private KnowledgeService service;

    /** 构造被测服务 / Build the service under test. */
    @BeforeEach
    void setUp() {
        service = new KnowledgeService(repository, vectorStore, metadataRepository, tokenizer,
                props, rerankService, eventPublisher, sourceRepository);
    }

    /** 构造最小合法请求 / Build a minimal valid request. */
    private KnowledgeRequest request() {
        KnowledgeRequest req = new KnowledgeRequest();
        req.setKind("metric");
        req.setTitle("GMV");
        req.setContent("成交总额，按订单金额求和");
        return req;
    }

    /**
     * 成功路径：元数据先落库，向量后写入，最终状态为 ready。
     * Success path: metadata is saved before vector upsert, final status is ready.
     */
    @Test
    @DisplayName("双写成功：save → upsertWithResult → updateStatus(ready)")
    void persistsMetadataBeforeVectorOnSuccess() {
        when(tokenizer.tokenize(anyString())).thenReturn(List.of("gmv", "成交"));
        when(vectorStore.upsertWithResult(any(KnowledgeEntry.class))).thenReturn(true);

        KnowledgeEntry entry = service.add(request());

        InOrder order = inOrder(repository, vectorStore);
        order.verify(repository).save(entry);
        order.verify(repository).indexTerms(anyString(), any());
        order.verify(vectorStore).upsertWithResult(entry);
        order.verify(repository).updateStatus(entry.getId(), "ready");
        assertThat(entry.getStatus()).isEqualTo("ready");
    }

    /**
     * 失败路径：向量写入失败时状态必须是 vector_failed，且仍保留元数据记录。
     * Failure path: vector failure marks the entry vector_failed while metadata remains.
     */
    @Test
    @DisplayName("双写失败：状态落为 vector_failed，保留元数据")
    void marksVectorFailedWhenUpsertFails() {
        when(tokenizer.tokenize(anyString())).thenReturn(List.of("gmv"));
        when(vectorStore.upsertWithResult(any(KnowledgeEntry.class))).thenReturn(false);

        KnowledgeEntry entry = service.add(request());

        verify(repository).save(entry);
        verify(repository).updateStatus(entry.getId(), "vector_failed");
        assertThat(entry.getStatus()).isEqualTo("vector_failed");
    }

    /**
     * 向量失败仍发布 ADD 事件：图谱基于元数据构建，向量失败不影响实体关系。
     * An ADD event is still published: the graph is built from metadata, so a vector
     * failure must not block entity-relationship sync.
     */
    @Test
    @DisplayName("向量失败仍发布 ADD 事件以同步图谱")
    void publishesAddEventEvenWhenVectorFails() {
        when(tokenizer.tokenize(anyString())).thenReturn(List.of("gmv"));
        when(vectorStore.upsertWithResult(any(KnowledgeEntry.class))).thenReturn(false);

        KnowledgeEntry entry = service.add(request());

        ArgumentCaptor<KnowledgeChangedEvent> event = ArgumentCaptor.forClass(KnowledgeChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        KnowledgeChangedEvent change = event.getValue();
        assertThat(change.getChangeType()).isEqualTo(KnowledgeChangedEvent.ChangeType.ADD);
        assertThat(change.getEntryId()).isEqualTo(entry.getId());
    }
}
