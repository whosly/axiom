package com.dataagent.embedding;

import com.dataagent.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 远程 API Embedding 策略 / Remote API embedding strategy.
 *
 * <p>调用 OpenAI 兼容的 /embeddings 端点（如 SiliconFlow）。
 * 这是原来的默认实现，现在作为 local 之外的可选项。
 * / Calls an OpenAI-compatible /embeddings endpoint (e.g. SiliconFlow). This was the
 * original default; now an alternative to the local provider.
 */
@Slf4j
@Component("apiEmbedding")
public class ApiEmbeddingProvider implements EmbeddingProvider {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public ApiEmbeddingProvider(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String providerName() { return "api"; }

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
            if (!response.isSuccessful()) {
                String err = response.body() == null ? "" : response.body().string();
                throw new IllegalStateException("Embedding API HTTP " + response.code() + ": " + err);
            }
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
    public int dimension() { return props.getLlm().getEmbedding().getDimension(); }
}
