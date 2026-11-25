package com.axiom.rerank;

import java.util.List;

/**
 * 重排序策略接口 / Reranking strategy interface.
 *
 * <p>召回阶段（向量+全文）返回候选后，rerank 对候选与问题的真实相关性重新打分排序。
 * / After recall (vector + full-text), rerank rescores and reorders candidates by
 * their true relevance to the question.
 */
public interface RerankProvider {

    /** 策略名称 / Strategy name. */
    String providerName();

    /**
     * 对候选按与查询的相关性重排 / Rerank candidates by relevance to the query.
     *
     * @param query      查询文本 / Query text.
     * @param candidates 候选文本列表 / Candidate texts.
     * @return 每个候选的分数（与 candidates 顺序一致）；返回 {@code null} 表示
     *         重排不可用（如 LLM 调用失败），调用方应保留召回原始分数——
     *         绝不能以 0 分占位，否则下游阈值过滤会把全部命中清空。
     *         / Scores aligned with candidates; {@code null} means rerank is
     *         unavailable (e.g. LLM call failed) and the caller must keep recall
     *         scores — zero-filling would let downstream threshold filters drop
     *         every hit.
     */
    List<Double> score(String query, List<String> candidates);
}
