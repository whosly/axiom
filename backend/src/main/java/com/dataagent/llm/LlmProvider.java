package com.dataagent.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * Strategy interface for LLM providers.
 * Implementations wrap a specific vendor's HTTP API (chat + embedding).
 * The rest of the application depends only on this abstraction.
 */
public interface LlmProvider {

    String providerName();

    /** Non-streaming chat completion. */
    String chat(List<ChatMessage> messages, ChatOptions options);

    /** Streaming chat completion; tokens are pushed to the consumer. */
    void chatStream(List<ChatMessage> messages, ChatOptions options, Consumer<String> tokenConsumer);

    /** Text embedding (uses a configurable OpenAI-compatible endpoint for DeepSeek). */
    float[] embed(String text);

    /** Embedding vector dimension. */
    int embeddingDimension();

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
