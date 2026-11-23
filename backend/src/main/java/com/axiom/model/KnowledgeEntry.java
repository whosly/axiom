package com.axiom.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一知识条目模型 / Unified knowledge entry model.
 *
 * <p>所有知识来源（指标口径、业务术语、分析模板、文档块、历史案例）都归一化为
 * 该结构，以便共享同一套存储和检索层。/ All knowledge sources (metric definitions,
 * business glossary, analysis recipes, document chunks, historical cases) are
 * normalized into this structure so they can share one storage and retrieval layer.
 *
 * <p>设计取舍：content 用于向量化，title/tags 用于精确过滤，sourceType/sourceId
 * 用于溯源和删除。/ Design trade-off: content is for embedding, title/tags are for
 * exact filtering, and sourceType/sourceId enable traceability and cascade deletion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEntry {

    /** 条目唯一 ID / Unique entry ID. */
    private String id;

    /**
     * 知识类型 / Knowledge kind.
     * 取值：metric | glossary | recipe | document | case / Values: metric | glossary | recipe | document | case.
     */
    private String kind;

    /** 标题或名称（指标名/术语名/文档标题）/ Title or name (metric name, term, document title). */
    private String title;

    /** 用于向量化的正文内容 / Text content used for embedding. */
    private String content;

    /**
     * 指标公式或结构化规则（仅 metric/recipe 使用，确定性执行，不经过向量检索）
     * / Metric formula or structured rule (only for metric/recipe; executed deterministically,
     * not via vector search).
     */
    private String formula;

    /** 关联字段列表（指标口径用）/ Associated field names (used by metric definitions). */
    private List<String> fields;

    /** 标签列表，用于结构化过滤 / Tags for structured filtering. */
    private List<String> tags;

    /**
     * 来源类型 / Source type.
     * 取值：manual | document | analysis | import / Values: manual | document | analysis | import.
     */
    private String sourceType;

    /** 来源 ID，用于溯源和级联删除 / Source ID for traceability and cascade deletion. */
    private String sourceId;

    /** 关联数据集 ID（为空表示全局知识）/ Associated dataset ID (null means global knowledge). */
    private String datasetId;

    /** 块序号（文档分块用）/ Chunk index (for document chunking). */
    @Builder.Default
    private int chunkIndex = 0;

    /** 内容哈希，用于幂等去重 / Content hash for idempotent deduplication. */
    private String textHash;

    /** 入库状态：pending | ready | vector_failed / Ingestion status. */
    @Builder.Default
    private String status = "ready";

    /** 版本号，每次更新递增 / Version number, incremented on each update. */
    @Builder.Default
    private int version = 1;

    /** 是否已软删除 / Whether the entry is soft-deleted. */
    @Builder.Default
    private boolean deleted = false;

    /** 创建时间 / Creation time. */
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    /** 更新时间 / Last update time. */
    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
