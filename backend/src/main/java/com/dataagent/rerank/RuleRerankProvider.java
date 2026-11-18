package com.dataagent.rerank;

import com.dataagent.service.KnowledgeTokenizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则重排序策略 / Rule-based reranking strategy.
 *
 * <p>无需 LLM，用查询分词与候选文本的覆盖率打分，适合无 API Key 的降级场景。
 * / No LLM needed; scores by token coverage between query and candidate, ideal for
 * the no-API-key degraded mode.
 *
 * <p>评分 = 查询分词命中率 × 0.7 + 原文子串命中 × 0.3
 * / Score = token hit ratio × 0.7 + raw substring hit × 0.3.
 */
@Component("ruleRerank")
@RequiredArgsConstructor
public class RuleRerankProvider implements RerankProvider {

    private final KnowledgeTokenizer tokenizer;

    @Override
    public String providerName() { return "rule"; }

    @Override
    public List<Double> score(String query, List<String> candidates) {
        Set<String> queryTokens = new HashSet<>(tokenizer.tokenize(query));
        String lowerQuery = query == null ? "" : query.toLowerCase();
        List<Double> scores = new ArrayList<>();
        for (String candidate : candidates) {
            String text = candidate == null ? "" : candidate.toLowerCase();
            if (queryTokens.isEmpty()) {
                scores.add(text.contains(lowerQuery) ? 1.0 : 0.0);
                continue;
            }
            int hit = 0;
            for (String t : queryTokens) if (text.contains(t)) hit++;
            double coverage = (double) hit / queryTokens.size();
            double substring = text.contains(lowerQuery) ? 1.0 : 0.0;
            scores.add(coverage * 0.7 + substring * 0.3);
        }
        return scores;
    }
}
