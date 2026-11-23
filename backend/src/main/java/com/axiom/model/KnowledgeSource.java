package com.axiom.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识来源实体 / Knowledge source entity.
 *
 * <p>一批知识条目（共享 sourceId）的元数据，支持按来源查看、统计与级联管理。
 * / Metadata of a batch of knowledge entries sharing a sourceId, enabling
 * source-level viewing, statistics and cascade management.
 */
@Data
public class KnowledgeSource {
    private String id;               // 对应 KnowledgeEntry.sourceId / Matches entry sourceId
    private String name;             // 显示名称 / Display name.
    private String type;             // manual | document | analysis | import | jdbc
    private String description;      // 描述 / Description.
    private int entryCount;          // 条目数 / Entry count.
    private String status;           // active | deleted
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
