package com.dataagent.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档分块请求 / Document chunk request.
 *
 * <p>继承 KnowledgeRequest，增加 chunkIndex 表达块在原文中的顺序。
 * Extends KnowledgeRequest with the chunk's position in the source document.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DocumentChunkRequest extends KnowledgeRequest {
    /** 块序号 / Chunk index. */
    private int chunkIndex;

    /** 来源文件名 / Source file name. */
    private String sourceName;
}
