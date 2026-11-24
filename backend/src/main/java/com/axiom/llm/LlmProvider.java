package com.axiom.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 对话策略接口 / LLM chat strategy interface.
 *
 * <p>只负责「对话」职责（chat / chatStream），embedding 由独立的
 * {@code EmbeddingProvider} 策略负责，两者解耦：一个厂商可以同时提供对话和向量，
 * 也可以只提供其一（如 DeepSeek 无 embedding，通义有）。
 * Only owns chat responsibilities; embedding is a separate strategy so a vendor may
 * provide chat only (DeepSeek) or both chat and embeddings (Qwen).
 *
 * <p>新增厂商：实现本接口并标注 {@code @Component("厂商名")} 即可被工厂自动发现。
 * To add a vendor: implement this interface and annotate {@code @Component("name")}.
 */
public interface LlmProvider {

    /** 策略名（与 @Component bean 名一致）/ Strategy name (matches @Component bean name). */
    String providerName();

    /** 展示名称，用于前端下拉 / Display name for the frontend selector. */
    default String displayName() { return providerName(); }

    /** 该厂商是否配置了可用密钥 / Whether a usable API key is configured. */
    boolean isAvailable();

    /** 当前对话模型名（供编排器传入 ChatOptions）/ Active chat model name. */
    String chatModel();

    /** 非流式对话 / Non-streaming chat completion. */
    String chat(List<ChatMessage> messages, ChatOptions options);

    /** 流式对话，token 通过回调推送 / Streaming chat; tokens pushed to the consumer. */
    void chatStream(List<ChatMessage> messages, ChatOptions options, Consumer<String> tokenConsumer);

    // ---- value objects ----

    record ChatMessage(String role, String content) {
        public static ChatMessage system(String c) { return new ChatMessage("system", c); }
        public static ChatMessage user(String c) { return new ChatMessage("user", c); }
        public static ChatMessage assistant(String c) { return new ChatMessage("assistant", c); }
    }

    record ChatOptions(String model, double temperature, int maxTokens, boolean jsonMode) {
        public static ChatOptions defaults(String model) {
            return new ChatOptions(model, 0.1, 4096, false);
        }
    }
}
