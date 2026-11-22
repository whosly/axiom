package com.dataagent.llm;

import com.dataagent.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 对话策略 / DeepSeek chat strategy.
 *
 * <p>DeepSeek 提供 OpenAI 兼容的 /chat/completions 端点，协议处理全部复用基类。
 * DeepSeek 不提供 embedding，向量能力由独立的 EmbeddingProvider 策略负责。
 * DeepSeek exposes an OpenAI-compatible endpoint; protocol handling is inherited.
 * Embeddings are handled by the separate EmbeddingProvider strategy.
 */
@Component("deepseek")
public class DeepSeekProvider extends OpenAiCompatibleProvider {

    public DeepSeekProvider(AppProperties props, ObjectMapper mapper) {
        super(props, mapper, "deepseek");
    }

    @Override
    public String displayName() { return "DeepSeek"; }
}
