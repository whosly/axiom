package com.dataagent.embedding;

import ai.onnxruntime.*;
import com.dataagent.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 ONNX Embedding 策略 / Local ONNX embedding strategy.
 *
 * <p>使用 bge-small-zh-v1.5 ONNX 模型在本地 CPU 上推理，完全不依赖外部服务。
 * 输出 512 维向量，取 [CLS] 位置隐藏状态并做 L2 归一化（BGE 模型标准做法）。
 * Uses the bge-small-zh-v1.5 ONNX model for local CPU inference with zero external
 * dependency. Emits 512-dim vectors from the [CLS] hidden state, L2-normalized
 * (standard for BGE models).
 *
 * <p>模型与词表路径由 app.embedding.local 配置，首次使用时懒加载（避免启动阻塞）。
 * Model and vocab paths come from config; lazily initialized on first use to avoid
 * blocking startup.
 */
@Slf4j
@Component("localEmbedding")
public class LocalEmbeddingProvider implements EmbeddingProvider {

    private final AppProperties props;
    private final BertTokenizer tokenizer;

    private OrtEnvironment env;
    private OrtSession session;
    private volatile boolean ready = false;

    public LocalEmbeddingProvider(AppProperties props, BertTokenizer tokenizer) {
        this.props = props;
        this.tokenizer = tokenizer;
    }

    /** 懒加载模型（双重检查锁）/ Lazily load the model (double-checked locking). */
    private void ensureLoaded() {
        if (ready) return;
        synchronized (this) {
            if (ready) return;
            var cfg = props.getEmbedding().getLocal();
            Path modelPath = Path.of(cfg.getModelPath());
            Path vocabPath = Path.of(cfg.getVocabPath());
            if (!Files.exists(modelPath) || !Files.exists(vocabPath)) {
                throw new IllegalStateException("本地 embedding 模型文件缺失 / Local embedding files missing: "
                        + modelPath + " , " + vocabPath);
            }
            try {
                // BasicTokenizer / WordPiece 加载词表 / Load vocab for tokenizer.
                tokenizer.load(vocabPath.toString());

                env = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(cfg.getThreads());
                session = env.createSession(modelPath.toString(), opts);
                ready = true;
                log.info("[LocalEmbedding] model loaded: {} (dim={}, threads={})",
                        modelPath.toAbsolutePath(), dimension(), cfg.getThreads());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load local embedding model", e);
            }
        }
    }

    @Override
    public String providerName() { return "local"; }

    @Override
    public float[] embed(String text) {
        ensureLoaded();
        int maxLen = props.getEmbedding().getLocal().getMaxLength();
        List<Long> ids = tokenizer.encode(text == null ? "" : text, maxLen);
        int seqLen = ids.size();

        long[] shape = {1, seqLen};
        long[] inputIds = new long[seqLen];
        long[] attentionMask = new long[seqLen];
        long[] tokenTypeIds = new long[seqLen];
        for (int i = 0; i < seqLen; i++) {
            inputIds[i] = ids.get(i);
            attentionMask[i] = 1L;
            tokenTypeIds[i] = 0L;
        }

        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(inputIds), shape);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(attentionMask), shape);
             OnnxTensor typeTensor = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(tokenTypeIds), shape)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idsTensor);
            inputs.put("attention_mask", maskTensor);
            inputs.put("token_type_ids", typeTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                // 输出 last_hidden_state [1, seqLen, 512] / Output last_hidden_state.
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                float[] cls = hidden[0][0]; // [CLS] 向量 / CLS vector.
                return l2Normalize(cls);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Local embedding inference failed: " + e.getMessage(), e);
        }
    }

    /** L2 归一化 / L2 normalization: v / ||v||. */
    private float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        double norm = Math.sqrt(sum);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    @Override
    public int dimension() { return props.getEmbedding().getLocal().getDimension(); }

    /** 是否已就绪 / Whether the model is loaded. */
    public boolean isReady() { return ready; }

    /** 释放 ONNX 资源 / Release ONNX resources. */
    @PreDestroy
    public void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
    }
}
