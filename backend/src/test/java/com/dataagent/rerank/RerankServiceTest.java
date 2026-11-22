package com.dataagent.rerank;

import com.dataagent.config.AppProperties;
import com.dataagent.llm.LlmProviderFactory;
import com.dataagent.service.KnowledgeVectorStore.Hit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重排序服务测试 / Tests for the rerank service.
 *
 * <p>验证 auto/llm/rule 策略解析与禁用、单命中短路等分支。
 * Verifies strategy resolution (auto/llm/rule), disabled mode and single-hit short-circuit.
 */
@ExtendWith(MockitoExtension.class)
class RerankServiceTest {

    @Mock private RerankProvider ruleProvider;
    @Mock private RerankProvider llmProvider;
    @Mock private LlmProviderFactory llmFactory;

    private AppProperties props;
    private RerankService service;

    /** 构造服务与策略映射 / Build service with provider map. */
    @BeforeEach
    void setUp() {
        props = new AppProperties();
        Map<String, RerankProvider> providers = new LinkedHashMap<>();
        providers.put("ruleRerank", ruleProvider);
        providers.put("llmRerank", llmProvider);
        service = new RerankService(providers, props, llmFactory);
    }

    /** 构造命中 / Build a hit. */
    private Hit hit(String id, float score) {
        return new Hit(id, "metric", id, "content", "src", score);
    }

    /** 单命中或禁用时短路，不调用 provider / Single hit or disabled short-circuits. */
    @Test
    @DisplayName("单命中短路")
    void shortCircuit() {
        var result = service.rerank("q", List.of(hit("a", 1.0f)), 5);
        assertThat(result).hasSize(1);
        verify(ruleProvider, never()).score(anyString(), anyList());

        props.getRerank().setEnabled(false);
        var result2 = service.rerank("q", List.of(hit("a", 1f), hit("b", 0.5f)), 1);
        assertThat(result2).hasSize(1);
        verify(ruleProvider, never()).score(anyString(), anyList());
    }

    /** auto + 无 Key 走规则 / auto with no key uses rule provider. */
    @Test
    @DisplayName("auto 无 Key 走规则")
    void autoWithoutKey() {
        when(llmFactory.isAvailable()).thenReturn(false);
        when(ruleProvider.score(anyString(), anyList())).thenReturn(List.of(0.9, 0.1));
        var result = service.rerank("q", List.of(hit("a", 0.5f), hit("b", 0.5f)), 2);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("a");
        verify(ruleProvider).score(anyString(), anyList());
    }

    /** auto + 有 Key 走 LLM / auto with key uses LLM provider. */
    @Test
    @DisplayName("auto 有 Key 走 LLM")
    void autoWithKey() {
        when(llmFactory.isAvailable()).thenReturn(true);
        when(llmProvider.score(anyString(), anyList())).thenReturn(List.of(0.1, 0.9));
        var result = service.rerank("q", List.of(hit("a", 0.5f), hit("b", 0.5f)), 2);
        assertThat(result.get(0).getId()).isEqualTo("b");
        verify(llmProvider).score(anyString(), anyList());
    }

    /** 显式 rule 策略 / Explicit rule strategy. */
    @Test
    @DisplayName("显式 rule 策略")
    void explicitRule() {
        props.getRerank().setProvider("rule");
        when(ruleProvider.score(anyString(), anyList())).thenReturn(List.of(0.2, 0.8));
        var result = service.rerank("q", List.of(hit("a", 0.5f), hit("b", 0.5f)), 2);
        assertThat(result.get(0).getId()).isEqualTo("b");
        verify(ruleProvider).score(anyString(), anyList());
    }
}
