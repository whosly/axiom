package com.dataagent.llm;

import com.dataagent.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议抽象基类 / Base class for OpenAI-compatible chat providers.
 *
 * <p>DeepSeek、通义千问等厂商都提供 OpenAI 兼容的 {@code /chat/completions} 端点，
 * 请求体、SSE 响应格式一致，仅 base-url / key / 模型名不同。把协议处理放在基类，
 * 子类只需声明策略名，新增厂商零重复代码。
 * DeepSeek and Qwen expose OpenAI-compatible endpoints with identical request/SSE shapes;
 * only base-url, key and model names differ. Subclasses only declare the strategy name.
 */
@Slf4j
public abstract class OpenAiCompatibleProvider implements LlmProvider {

    protected final AppProperties props;
    protected final ObjectMapper mapper;
    protected final OkHttpClient http;
    private final String strategyName;

    protected OpenAiCompatibleProvider(AppProperties props, ObjectMapper mapper, String strategyName) {
        this.props = props;
        this.mapper = mapper;
        this.strategyName = strategyName;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /** 当前厂商配置 / Config of this vendor. */
    protected LlmProviderConfig cfg() {
        return props.getLlm().config(strategyName);
    }

    @Override
    public String providerName() { return strategyName; }

    @Override
    public boolean isAvailable() { return cfg().hasKey(); }

    @Override
    public String chatModel() { return cfg().getChatModel(); }

    @Override
    public String reasonerModel() {
        return cfg().getReasonerModel() == null || cfg().getReasonerModel().isBlank()
                ? chatModel() : cfg().getReasonerModel();
    }

    @Override
    public String chat(List<ChatMessage> messages, ChatOptions options) {
        Request request = buildChatRequest(messages, options, false);
        try (Response response = http.newCall(request).execute()) {
            ensureSuccess(response);
            JsonNode root = mapper.readTree(response.body().string());
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (IOException e) {
            throw new IllegalStateException(strategyName + " chat request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatStream(List<ChatMessage> messages, ChatOptions options, Consumer<String> tokenConsumer) {
        Request request = buildChatRequest(messages, options, true);
        try (Response response = http.newCall(request).execute()) {
            ensureSuccess(response);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonNode node = mapper.readTree(data);
                        JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                        if (!delta.isMissingNode() && !delta.isNull()) tokenConsumer.accept(delta.asText());
                    } catch (Exception ignored) {
                        // 跳过格式异常的 SSE 分片 / Skip malformed SSE fragments.
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(strategyName + " stream request failed: " + e.getMessage(), e);
        }
    }

    /** 构建 chat/completions 请求 / Build the chat/completions request. */
    protected Request buildChatRequest(List<ChatMessage> messages, ChatOptions options, boolean stream) {
        LlmProviderConfig c = cfg();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", options.model() != null ? options.model() : c.getChatModel());
        body.put("temperature", options.temperature());
        body.put("max_tokens", options.maxTokens());
        body.put("stream", stream);
        if (options.jsonMode()) {
            ObjectNode fmt = mapper.createObjectNode();
            fmt.put("type", "json_object");
            body.set("response_format", fmt);
        }
        ArrayNode arr = mapper.createArrayNode();
        for (ChatMessage m : messages) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", m.role());
            node.put("content", m.content());
            arr.add(node);
        }
        body.set("messages", arr);

        try {
            return new Request.Builder()
                    .url(c.getBaseUrl().replaceAll("/$", "") + endpoint())
                    .header("Authorization", "Bearer " + c.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8),
                            MediaType.parse("application/json")))
                    .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat request", e);
        }
    }

    /** 对话端点路径，默认 OpenAI 兼容的 /chat/completions / Chat endpoint path. */
    protected String endpoint() { return "/chat/completions"; }

    protected void ensureSuccess(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String err = response.body() != null ? response.body().string() : "";
            throw new IOException("HTTP " + response.code() + ": " + err);
        }
    }
}
