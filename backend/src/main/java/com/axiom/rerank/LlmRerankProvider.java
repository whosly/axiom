package com.axiom.rerank;

import com.axiom.llm.LlmProvider;
import com.axiom.llm.LlmProviderFactory;
import com.axiom.llm.LlmProvider.ChatOptions;
import com.axiom.llm.LlmProvider.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 重排序策略 / LLM-based reranking strategy.
 *
 * <p>让模型对每个「查询-候选」对打 0~1 相关性分，语义理解更准，但消耗 token。
 * / Asks the model to score each query-candidate pair from 0 to 1; more accurate
 * semantic understanding at the cost of tokens.
 *
 * <p>批量一次请求打分，降低延迟 / Scores in a single batched request to reduce latency.
 */
@Slf4j
@Component("llmRerank")
@RequiredArgsConstructor
public class LlmRerankProvider implements RerankProvider {

    private final LlmProviderFactory llmFactory;
    private final ObjectMapper mapper;

    @Override
    public String providerName() { return "llm"; }

    @Override
    public List<Double> score(String query, List<String> candidates) {
        if (candidates.isEmpty()) return List.of();
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请判断每个候选与问题的相关性，输出 JSON 数组（0~1 的小数），不要其他文字。\n");
            prompt.append("Score relevance of each candidate to the query. Output a JSON array of numbers 0~1 only.\n");
            prompt.append("问题 / Query: ").append(query).append("\n候选 / Candidates:\n");
            for (int i = 0; i < candidates.size(); i++) {
                String c = candidates.get(i).replace("\n", " ");
                if (c.length() > 300) c = c.substring(0, 300);
                prompt.append(i).append(". ").append(c).append("\n");
            }

            LlmProvider llm = llmFactory.current();
            String model = llm.chatModel();
            String response = llm.chat(List.of(ChatMessage.user(prompt.toString())),
                    new ChatOptions(model, 0.0, 512, true));

            String cleaned = response.trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(cleaned);
            if (arr.isArray()) {
                List<Double> scores = new ArrayList<>();
                for (int i = 0; i < candidates.size(); i++) {
                    double s = i < arr.size() ? arr.get(i).asDouble() : 0.0;
                    scores.add(Math.max(0.0, Math.min(1.0, s)));
                }
                return scores;
            }
        } catch (Exception e) {
            // 失败返回 null（= 重排不可用），由 RerankService 保留召回原始分数。
            // 全 0 分占位会让 retrieveContext 的 0.05 阈值把命中全部滤空——
            // LLM 限流/抖动时知识注入静默消失，比不用 rerank 更糟。
            // / Return null on failure; zero-filling silently wipes all knowledge
            // hits at the downstream 0.05 threshold — worse than no rerank at all.
            log.warn("[LlmRerank] failed, keep recall scores: {}", e.getMessage());
        }
        return null;
    }
}
