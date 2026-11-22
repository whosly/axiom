package com.dataagent.rerank;

import com.dataagent.llm.LlmProvider;
import com.dataagent.llm.LlmProviderFactory;
import com.dataagent.llm.LlmProvider.ChatOptions;
import com.dataagent.llm.LlmProvider.ChatMessage;
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
        List<Double> fallback = new ArrayList<>(java.util.Collections.nCopies(candidates.size(), 0.0));
        if (candidates.isEmpty()) return fallback;
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
            log.warn("[LlmRerank] failed, fallback to zeros: {}", e.getMessage());
        }
        return fallback;
    }
}
