package com.axiom.rerank;

import com.axiom.config.AppProperties;
import com.axiom.llm.LlmProviderFactory;
import com.axiom.service.KnowledgeVectorStore.Hit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 重排序服务 / Reranking service.
 *
 * <p>根据配置选择策略（auto/llm/rule），对检索候选重排并重设分数。
 * auto 模式下，有 DeepSeek API Key 用 LLM，否则用规则，保证零配置可用。
 * Selects strategy (auto/llm/rule), reranks candidates and resets scores. In auto
 * mode, uses LLM when a DeepSeek API key exists, otherwise rule-based (zero-config).
 */
@Slf4j
@Service
public class RerankService {

    private final Map<String, RerankProvider> providers;
    private final AppProperties props;
    private final LlmProviderFactory llmFactory;

    public RerankService(Map<String, RerankProvider> providers, AppProperties props, LlmProviderFactory llmFactory) {
        this.providers = providers;
        this.props = props;
        this.llmFactory = llmFactory;
    }

    /**
     * 对检索命中重排 / Rerank retrieval hits.
     *
     * @param query 查询文本 / Query text.
     * @param hits  检索命中（按原分数排序）/ Retrieval hits (sorted by original score).
     * @param topK  最终返回条数 / Final result count.
     * @return 重排后的命中 / Reranked hits.
     */
    public List<Hit> rerank(String query, List<Hit> hits, int topK) {
        if (!props.getRerank().isEnabled() || hits == null || hits.size() <= 1) {
            return hits == null ? List.of() : hits.subList(0, Math.min(topK, hits.size()));
        }

        RerankProvider provider = resolveProvider();
        List<String> texts = hits.stream().map(h -> h.getTitle() + " " + h.getContent()).toList();
        List<Double> scores = provider.score(query, texts);

        List<Hit> result = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            // 保留原命中用于溯源，用 rerank 分数覆盖展示分数 / Keep original for traceability,
            // overwrite the displayed score with the rerank score.
            double s = i < scores.size() ? scores.get(i) : h.getScore();
            Hit r = new Hit(h.getId(), h.getKind(), h.getTitle(), h.getContent(), h.getSourceId(), (float) s);
            result.add(r);
        }
        result.sort(Comparator.comparingDouble(Hit::getScore).reversed());
        log.debug("[Rerank] provider={} candidates={}", provider.providerName(), result.size());
        return result.subList(0, Math.min(topK, result.size()));
    }

    /** 按配置解析策略 / Resolve strategy by configuration. */
    private RerankProvider resolveProvider() {
        String cfg = props.getRerank().getProvider();
        boolean hasKey = llmFactory.isAvailable();

        String name;
        if ("auto".equalsIgnoreCase(cfg)) name = hasKey ? "llmRerank" : "ruleRerank";
        else if ("llm".equalsIgnoreCase(cfg)) name = "llmRerank";
        else name = "ruleRerank";

        RerankProvider p = providers.get(name);
        return p == null ? providers.get("ruleRerank") : p;
    }
}
