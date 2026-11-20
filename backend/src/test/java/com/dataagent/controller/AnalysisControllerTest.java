package com.dataagent.controller;

import com.dataagent.llm.LlmProvider;
import com.dataagent.llm.LlmProviderFactory;
import com.dataagent.repository.MetadataRepository;
import com.dataagent.service.AnalysisOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 对话入口单元测试 / Unit tests for the SSE chat endpoint.
 *
 * <p>聚焦同步入参校验：无效请求必须立即完成 emitter 且不进入异步分析流程，
 * 避免非法请求消耗 LLM 配额。
 * Invalid requests complete the emitter synchronously without entering async analysis,
 * so they cannot consume LLM quota.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisControllerTest {

    @Mock private AnalysisOrchestrator orchestrator;
    @Mock private MetadataRepository metaRepo;
    @Mock private LlmProviderFactory llmFactory;

    private AnalysisController controller;

    /** 构造被测 Controller / Build the controller under test. */
    @BeforeEach
    void setUp() {
        controller = new AnalysisController(orchestrator, metaRepo, new ObjectMapper(), llmFactory);
    }

    /**
     * 空消息应同步返回 emitter，且不触发编排与落库。
     * A blank message returns an emitter synchronously without planning or persistence.
     */
    @Test
    @DisplayName("空消息：不调用编排器与会话落库")
    void blankMessageDoesNotStartAnalysis() {
        SseEmitter emitter = controller.chat(Map.of("message", "  ", "datasetId", "ds-1"));

        assertThat(emitter).isNotNull();
        verify(orchestrator, never()).plan(any(), anyString());
        verify(metaRepo, never()).saveSession(any());
        verify(metaRepo, never()).saveMessage(anyString(), any(), any());
    }

    /**
     * 缺少数据集应同步完成，不查询元数据库、不触发分析。
     * A missing dataset completes synchronously without metadata lookup or analysis.
     */
    @Test
    @DisplayName("缺少数据集：不查询数据集、不调用编排器")
    void missingDatasetDoesNotStartAnalysis() {
        SseEmitter emitter = controller.chat(Map.of("message", "分析趋势"));

        assertThat(emitter).isNotNull();
        verify(metaRepo, never()).getDataset(anyString());
        verify(orchestrator, never()).plan(any(), anyString());
    }

    /**
     * 健康检查应返回 ok 与当前 provider 名称。
     * Health check returns ok and the current provider name.
     */
    @Test
    @DisplayName("健康检查返回 ok 与 provider 名称")
    void healthReportsOk() {
        LlmProvider provider = mock(LlmProvider.class);
        when(llmFactory.current()).thenReturn(provider);
        when(provider.providerName()).thenReturn("deepseek");

        Map<String, Object> body = controller.health();

        assertThat(body).containsEntry("status", "ok");
        assertThat(body).containsEntry("llmProvider", "deepseek");
        assertThat(body).containsKey("timestamp");
    }
}
