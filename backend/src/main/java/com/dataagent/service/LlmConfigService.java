package com.dataagent.service;

import com.dataagent.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时 LLM 配置服务 / Runtime LLM configuration service.
 *
 * <p>直接修改 Spring 配置属性对象（可变 bean）实现热更新，无需重启。
 * DeepSeekProvider 每次构建请求时读取该对象，因此更新立即生效。
 * Hot-reloads by mutating the Spring configuration bean; DeepSeekProvider reads it
 * on every request, so changes take effect immediately without a restart.
 */
@Slf4j
@Service
public class LlmConfigService {

    private final AppProperties props;

    public LlmConfigService(AppProperties props) {
        this.props = props;
    }

    /**
     * 返回当前 LLM 配置（脱敏，不返回完整 API Key）。
     * Return current LLM configuration with the API key masked.
     *
     * @return 配置映射 / Configuration map
     */
    public Map<String, Object> current() {
        var cfg = props.getLlm().getDeepseek();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", props.getLlm().getProvider());
        result.put("baseUrl", cfg.getBaseUrl());
        result.put("chatModel", cfg.getChatModel());
        result.put("reasonerModel", cfg.getReasonerModel());
        result.put("temperature", cfg.getTemperature());
        result.put("maxTokens", cfg.getMaxTokens());
        String key = cfg.getApiKey();
        result.put("apiKeySet", key != null && !key.isBlank());
        result.put("apiKeyMasked", key == null || key.isBlank() ? "" : mask(key));
        return result;
    }

    /**
     * 更新 LLM 配置并立即生效 / Update LLM configuration with immediate effect.
     *
     * @param body 配置键（provider/baseUrl/apiKey/chatModel/reasonerModel/temperature/maxTokens）
     *             Configuration keys
     * @return 更新后的脱敏配置 / Updated masked configuration
     */
    public Map<String, Object> update(Map<String, String> body) {
        var cfg = props.getLlm().getDeepseek();
        if (body.containsKey("apiKey")) cfg.setApiKey(body.get("apiKey"));
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
        if (body.containsKey("provider") && !body.get("provider").isBlank()) {
            props.getLlm().setProvider(body.get("provider"));
        }
        log.info("[LLMConfig] updated: provider={}, model={}, keySet={}",
                props.getLlm().getProvider(), cfg.getChatModel(),
                cfg.getApiKey() != null && !cfg.getApiKey().isBlank());
        return current();
    }

    /** 脱敏 API Key：只显示末 4 位 / Mask the API key, showing only the last 4 chars. */
    private String mask(String key) {
        if (key.length() <= 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
