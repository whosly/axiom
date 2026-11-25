package com.axiom.llm;

import lombok.Data;

/**
 * LLM 厂商通用配置 / Common configuration shared by all LLM vendors.
 *
 * <p>所有走 OpenAI 兼容协议的厂商（DeepSeek、通义千问等）共用这一组字段。
 * 注意：新增厂商仍需一个标注 {@code @Component("名字")} 的 Provider 类供工厂发现，
 * 配置块只提供参数（详见 docs/LLM_PROVIDER_GUIDE.md）；仅切换同一厂商的模型则只改配置。
 * All OpenAI-compatible vendors share these fields. Adding a vendor still needs a
 * {@code @Component("name")} Provider bean; the config block only supplies parameters
 * (see docs/LLM_PROVIDER_GUIDE.md). Switching models within a vendor only needs config.
 */
@Data
public class LlmProviderConfig {
    /** API 基础地址 / API base URL. */
    private String baseUrl = "";
    /** API 密钥 / API key. */
    private String apiKey = "";
    /** 对话模型（用于 plan 与 conclusion）/ Chat model (for plan and conclusion). */
    private String chatModel = "";
    /** 推理模型（保留字段，供需要区分推理/对话的厂商使用）/ Reasoner model (optional). */
    private String reasonerModel = "";
    /** 采样温度 / Sampling temperature. */
    private double temperature = 0.1;
    /** 单次最大输出 token / Max output tokens. */
    private int maxTokens = 4096;

    /** 是否配置了可用密钥 / Whether a usable API key is configured. */
    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 用持久化配置覆盖当前值（仅非空字段），避免空 apiKey 清掉环境变量注入的密钥。
     * Overlay persisted values onto current config, ignoring blank fields so an empty
     * persisted apiKey cannot wipe one supplied via environment variables.
     */
    public void mergeFrom(LlmProviderConfig other) {
        if (other.baseUrl != null && !other.baseUrl.isBlank()) this.baseUrl = other.baseUrl;
        if (other.apiKey != null && !other.apiKey.isBlank()) this.apiKey = other.apiKey;
        if (other.chatModel != null && !other.chatModel.isBlank()) this.chatModel = other.chatModel;
        if (other.reasonerModel != null && !other.reasonerModel.isBlank()) this.reasonerModel = other.reasonerModel;
        // temperature=0（完全确定性输出）是合法值，不能当「未设置」丢弃——应用持久化的
        // 配置总是显式序列化该字段；负数视为无效未设置。/ 0 is a legal temperature (fully
        // deterministic); app-persisted configs always serialize it explicitly, negatives are unset.
        if (other.temperature >= 0) this.temperature = other.temperature;
        if (other.maxTokens != 0) this.maxTokens = other.maxTokens;
    }
}
