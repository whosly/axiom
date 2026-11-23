package com.axiom.model;

import lombok.Data;

import java.util.List;

/**
 * 知识录入请求 / Knowledge entry creation request.
 *
 * <p>用于手动录入指标口径、业务术语、分析模板等结构化知识。
 * Used for manually entering structured knowledge such as metric definitions,
 * business glossary and analysis recipes.
 */
@Data
public class KnowledgeRequest {

    /** 知识类型：metric | glossary | recipe / Knowledge kind. */
    private String kind;

    /** 标题（指标名/术语名）/ Title (metric name or term). */
    private String title;

    /** 正文内容 / Text content. */
    private String content;

    /** 指标公式（确定性执行，可选）/ Metric formula (optional, executed deterministically). */
    private String formula;

    /** 关联字段列表 / Associated field names. */
    private List<String> fields;

    /** 标签 / Tags. */
    private List<String> tags;

    /** 关联数据集 ID（可空表示全局）/ Dataset ID (null means global). */
    private String datasetId;
}
