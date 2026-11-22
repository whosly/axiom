package com.dataagent.llm;

import lombok.Data;

/**
 * LLM 厂商通用配置 / Common configuration shared by all LLM vendors.
 *
 * <p>所有走 OpenAI 兼容协议的厂商（DeepSeek、通义千问等）共用这一组字段，
 * 新增厂商只需在 application.yml 增加一个同名配置块，无需新增 Java 类。
 * All OpenAI-compatible vendors (DeepSeek, Qwen, etc.) share these fields;
 * adding a vendor only requires a config block in application.yml, no new Java class.
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
}
