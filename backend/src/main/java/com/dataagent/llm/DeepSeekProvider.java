package com.dataagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dataagent.config.AppProperties;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DeepSeek strategy implementation.
 * Chat uses DeepSeek's OpenAI-compatible API; embeddings use a separate
 * OpenAI-compatible endpoint because DeepSeek does not expose an embedding model.
 */
@Component("deepseek")
public class DeepSeekProvider implements LlmProvider {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public DeepSeekProvider(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String providerName() { return "deepseek"; }

    @Override
    public String chat(List<ChatMessage> messages, ChatOptions options) {
        Request request = buildChatRequest(messages, options, false);
        try (Response response = http.newCall(request).execute()) {
            ensureSuccess(response);
            JsonNode root = mapper.readTree(response.body().string());
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (IOException e) {
            throw new IllegalStateException("DeepSeek chat request failed: " + e.getMessage(), e);
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
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            JsonNode node = mapper.readTree(data);
                            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                            if (!delta.isMissingNode() && !delta.isNull()) {
                                tokenConsumer.accept(delta.asText());
                            }
                        } catch (Exception ignored) {
                            // skip malformed SSE fragments
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("DeepSeek stream request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public float[] embed(String text) {
        var emb = props.getLlm().getEmbedding();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", emb.getModel());
        body.put("input", text);

        Request request;
        try {
            request = new Request.Builder()
                    .url(emb.getBaseUrl().replaceAll("/$", "") + "/embeddings")
                    .header("Authorization", "Bearer " + emb.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8),
                            MediaType.parse("application/json")))
                    .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize embedding request", e);
        }

        try (Response response = http.newCall(request).execute()) {
            ensureSuccess(response);
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode vector = root.path("data").path(0).path("embedding");
            float[] result = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) result[i] = (float) vector.get(i).asDouble();
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Embedding request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int embeddingDimension() { return props.getLlm().getEmbedding().getDimension(); }

    // ---- internals ----

    private Request buildChatRequest(List<ChatMessage> messages, ChatOptions options, boolean stream) {
        var cfg = props.getLlm().getDeepseek();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", options.model() != null ? options.model() : cfg.getChatModel());
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
                    .url(cfg.getBaseUrl().replaceAll("/$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8),
                            MediaType.parse("application/json")))
                    .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chat request", e);
        }
    }

    private void ensureSuccess(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String err = response.body() != null ? response.body().string() : "";
            throw new IOException("HTTP " + response.code() + ": " + err);
        }
    }
}
