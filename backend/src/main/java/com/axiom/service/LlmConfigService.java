package com.axiom.service;

import com.axiom.config.AppProperties;
import com.axiom.llm.LlmProvider;
import com.axiom.llm.LlmProviderConfig;
import com.axiom.llm.LlmProviderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时 LLM 配置服务 / Runtime LLM configuration service.
 *
 * <p>直接修改 Spring 配置属性 bean 实现热更新，无需重启。所有厂商共享
 * {@link LlmProviderConfig} 结构，配置读写针对「当前厂商」，因此新增厂商零改动。
 * Hot-reloads by mutating the Spring config bean. All vendors share LlmProviderConfig,
 * and read/write target the active vendor, so adding a vendor needs no changes here.
 */
@Slf4j
@Service
public class LlmConfigService {

    private final AppProperties props;
    private final LlmProviderFactory llmFactory;
    private final LlmConfigStore configStore;

    public LlmConfigService(AppProperties props, LlmProviderFactory llmFactory, LlmConfigStore configStore) {
        this.props = props;
        this.llmFactory = llmFactory;
        this.configStore = configStore;
    }

    /**
     * 返回当前 LLM 配置（脱敏，不返回完整 API Key）。
     * Return current LLM configuration with the API key masked.
     *
     * @return 配置映射 / Configuration map
     */
    public Map<String, Object> current() {
        String active = props.getLlm().getProvider();
        LlmProviderConfig cfg = props.getLlm().config(active);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", active);
        result.put("displayName", displayNameOf(active));
        result.put("baseUrl", cfg.getBaseUrl());
        result.put("chatModel", cfg.getChatModel());
        result.put("reasonerModel", cfg.getReasonerModel());
        result.put("temperature", cfg.getTemperature());
        result.put("maxTokens", cfg.getMaxTokens());
        String key = cfg.getApiKey();
        result.put("apiKeySet", cfg.hasKey());
        result.put("apiKeyMasked", cfg.hasKey() ? mask(key) : "");
        result.put("availableProviders", llmFactory.options());
        return result;
    }

    /**
     * 更新 LLM 配置并立即生效 / Update LLM configuration with immediate effect.
     *
     * @param body 配置项（provider/baseUrl/apiKey/chatModel/reasonerModel/temperature/maxTokens）
     *             Configuration items
     * @return 更新后的脱敏配置 / Updated masked configuration
     */
    public Map<String, Object> update(Map<String, String> body) {
        // 切换厂商：只改策略选择，不动各厂商自己的配置 / Switch vendor: only change the strategy selector.
        if (body.containsKey("provider") && !body.get("provider").isBlank()) {
            String target = body.get("provider");
            LlmProvider p = llmFactory.get(target);
            if (p == null) {
                throw new IllegalArgumentException("Unknown LLM provider: " + target
                        + ". Available: " + llmFactory.all().keySet());
            }
            props.getLlm().setProvider(target);
            log.info("[LLMConfig] switched provider to {}", target);
        }

        // 其余字段写入「当前厂商」配置 / Remaining fields go to the active vendor's config.
        String active = props.getLlm().getProvider();
        LlmProviderConfig cfg = props.getLlm().config(active);
        // apiKey 与其他字段一致：空白视为「未修改」。配置面板提交时未输入 key 的空串
        // 若直接写入会静默清掉现有密钥，导致 llmAvailable 变 false 全链路降级。
        // 需要显式清除密钥时走配置文件编辑，不用空串覆盖。
        // / apiKey follows the other fields: blank means "unchanged". A blank submit
        // from the config panel must not silently wipe the existing key and degrade
        // the whole pipeline; explicit removal goes through the config file.
        if (body.containsKey("apiKey") && !body.get("apiKey").isBlank()) cfg.setApiKey(body.get("apiKey"));
        if (body.containsKey("baseUrl") && !body.get("baseUrl").isBlank()) cfg.setBaseUrl(body.get("baseUrl"));
        if (body.containsKey("chatModel") && !body.get("chatModel").isBlank()) cfg.setChatModel(body.get("chatModel"));
        if (body.containsKey("reasonerModel") && !body.get("reasonerModel").isBlank())
            cfg.setReasonerModel(body.get("reasonerModel"));
        if (body.containsKey("temperature")) {
            try { cfg.setTemperature(Double.parseDouble(body.get("temperature"))); }
            catch (NumberFormatException ignored) { /* 保留原值 / keep previous value */ }
        }
        if (body.containsKey("maxTokens")) {
            try { cfg.setMaxTokens(Integer.parseInt(body.get("maxTokens"))); }
            catch (NumberFormatException ignored) { /* 保留原值 / keep previous value */ }
        }
        // 持久化到 data/llm-config.json，重启后仍生效 / Persist so it survives restarts.
        configStore.save();
        log.info("[LLMConfig] updated provider={} model={} keySet={}",
                active, cfg.getChatModel(), cfg.hasKey());
        return current();
    }

    /** 厂商展示名 / Display name of a provider. */
    private String displayNameOf(String name) {
        LlmProvider p = llmFactory.get(name);
        return p == null ? name : p.displayName();
    }

    /** 脱敏 API Key：只显示末 4 位 / Mask the API key, showing only the last 4 chars. */
    private String mask(String key) {
        if (key == null || key.length() <= 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
