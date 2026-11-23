package com.axiom.rerank;

import com.axiom.service.KnowledgeTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则重排序测试 / Tests for rule-based reranking.
 */
class RuleRerankProviderTest {

    private final RuleRerankProvider provider = new RuleRerankProvider(new KnowledgeTokenizer());

    /** 命中查询词的候选得分更高 / Candidates matching query tokens score higher. */
    @Test
    @DisplayName("命中查询词得分更高")
    void matchingScoresHigher() {
        List<Double> scores = provider.score("GMV 定义", List.of(
                "GMV 是成交总额的定义", "完全不相关的文本"));
        assertThat(scores).hasSize(2);
        assertThat(scores.get(0)).isGreaterThan(scores.get(1));
    }

    /** 空候选返回空列表 / Empty candidates return empty list. */
    @Test
    @DisplayName("空候选列表")
    void emptyCandidates() {
        assertThat(provider.score("query", List.of())).isEmpty();
    }

    /** 策略名稳定 / Stable provider name. */
    @Test
    @DisplayName("策略名为 rule")
    void providerName() {
        assertThat(provider.providerName()).isEqualTo("rule");
    }
}
