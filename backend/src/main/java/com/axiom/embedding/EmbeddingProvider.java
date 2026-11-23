package com.axiom.embedding;

/**
 * Embedding 策略接口 / Embedding strategy interface.
 *
 * <p>把「文本转向量」从 LLM chat 能力中独立出来，允许本地 ONNX 模型与远程 API
 * 两种实现自由切换。/ Decouples "text to vector" from LLM chat, allowing local ONNX
 * model and remote API implementations to be switched freely.
 */
public interface EmbeddingProvider {

    /** 策略名称 / Strategy name. */
    String providerName();

    /**
     * 生成文本向量 / Embed text into a vector.
     *
     * @param text 输入文本 / Input text.
     * @return 向量（通常已 L2 归一化）/ Vector (usually L2-normalized).
     */
    float[] embed(String text);

    /** 向量维度 / Vector dimension. */
    int dimension();
}
