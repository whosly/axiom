package com.axiom.llm;

import com.axiom.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 策略工厂 / LLM strategy factory.
 *
 * <p>从 Spring 容器自动发现所有 {@link LlmProvider} bean（@Component("名字")），
 * 按配置选择当前策略。新增厂商只需新增一个 bean + 一段配置，工厂零改动。
 * Auto-discovers all LlmProvider beans and selects the configured one; adding a vendor
 * needs only a new bean + config, with no factory changes.
 */
@Slf4j
@Component
public class LlmProviderFactory {

    private final Map<String, LlmProvider> providers;
    private final AppProperties props;

    public LlmProviderFactory(Map<String, LlmProvider> providers, AppProperties props) {
        this.providers = providers;
        this.props = props;
    }

    /**
     * 当前策略；配置名不存在时回退到第一个可用策略。
     * Current strategy; falls back to the first available provider on unknown config.
     */
    public LlmProvider current() {
        String name = props.getLlm().getProvider();
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            log.warn("[LLM] unknown provider '{}', available: {}", name, providers.keySet());
            provider = providers.values().stream().findFirst().orElse(null);
        }
        if (provider == null) {
            throw new IllegalStateException("No LLM provider registered");
        }
        return provider;
    }

    /** 按名称取策略 / Get a provider by strategy name. */
    public LlmProvider get(String name) {
        return providers.get(name);
    }

    /** 当前是否有可用 LLM（密钥已配置）/ Whether the active provider has a usable key. */
    public boolean isAvailable() {
        return current().isAvailable();
    }

    /**
     * 前端模型下拉选项 / Model options for the frontend selector.
     *
     * @return [{modelId: 策略名, name: 展示名}, ...]
     */
    public List<Map<String, String>> options() {
        // 按策略名排序，保证 deepseek 在前、结果稳定 / Sort by name for stable ordering.
        return providers.values().stream()
                .sorted(java.util.Comparator.comparing(LlmProvider::providerName))
                .map(p -> Map.of("modelId", p.providerName(), "name", p.displayName()))
                .toList();
    }

    /** 已注册策略名 / Names of registered providers. */
    public Map<String, LlmProvider> all() {
        return new LinkedHashMap<>(providers);
    }
}
