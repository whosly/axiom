package com.axiom.model;

import com.axiom.service.KnowledgeVectorStore.VectorStatus;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 知识条目详情模型 / Knowledge entry detail model.
 *
 * <p>聚合展示一条知识的完整信息：元数据、向量状态、溯源来源、处理链路。
 * 供管理页「查看详情」使用，回答「这条知识从哪来、怎么处理的、存成了什么样」。
 * Aggregates full information of one knowledge entry: metadata, vector status,
 * traceability and processing lineage. Used by the "view detail" UI to answer
 * "where did it come from, how was it processed, and what was stored".
 */
@Data
public class KnowledgeDetail {

    /** 知识条目本体 / The knowledge entry itself. */
    private KnowledgeEntry entry;

    /** 向量存储状态 / Vector storage status. */
    private VectorStatus vector;

    /** 溯源信息（来源类型对应的可读描述）/ Traceability (human-readable source description). */
    private String sourceLabel;

    /** 关联的数据集名称（若有）/ Associated dataset name (if any). */
    private String datasetName;

    /**
     * 处理链路 / Processing lineage.
     * 每一步含 stage（阶段）和 detail（说明）/ Each step has a stage and detail.
     */
    private List<Map<String, String>> lineage;

    /** 存储位置说明 / Storage location description. */
    private Map<String, String> storage;
}
