package com.dataagent.embedding;

import com.dataagent.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Embedding 策略工厂 / Embedding strategy factory.
 *
 * <p>根据配置选择本地 ONNX 或远程 API 实现；找不到时回退到本地。
 * Selects local ONNX or remote API by configuration; falls back to local if missing.
 */
@Component
public class EmbeddingProviderFactory {

    private final Map<String, EmbeddingProvider> providers;
    private final AppProperties props;

    public EmbeddingProviderFactory(Map<String, EmbeddingProvider> providers, AppProperties props) {
        this.providers = providers;
        this.props = props;
    }

    /**
     * 当前策略 / Current strategy.
     *
     * <p>配置名（local/api）与 Spring Bean 名（localEmbedding/apiEmbedding）不同，
     * 这里做别名映射。/ Config names (local/api) differ from Spring bean names
     * (localEmbedding/apiEmbedding), so map aliases here.
     */
    public EmbeddingProvider current() {
        String name = props.getEmbedding().getProvider();
        String beanName = switch (name.toLowerCase()) {
            case "local" -> "localEmbedding";
            case "api" -> "apiEmbedding";
            default -> name;
        };
        EmbeddingProvider p = providers.get(beanName);
        if (p == null) {
            throw new IllegalArgumentException("Unknown embedding provider: " + name
                    + ". Available: " + providers.keySet());
        }
        return p;
    }
}
