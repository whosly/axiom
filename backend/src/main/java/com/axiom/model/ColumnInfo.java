package com.axiom.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据集列元数据 / Dataset column metadata.
 *
 * <p>name 已做 SQL 安全净化；type 用于分析计划的字段类型校验；
 * sampleValues 作为 few-shot 样例喂给 LLM 辅助字段映射。
 * / name is SQL-sanitized; type feeds plan validation; sampleValues act as
 * few-shot examples for LLM field mapping.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnInfo {
    /** 净化后的 SQL 安全列名 / Sanitized SQL-safe column name. */
    private String name;
    /** numeric | temporal | categorical | boolean */
    private String type;
    /** 去重样例值（最多 3 个）/ Deduplicated sample values (up to 3). */
    private String sampleValues;
}
