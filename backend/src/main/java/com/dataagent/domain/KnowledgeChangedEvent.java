package com.dataagent.domain;

import org.springframework.context.ApplicationEvent;

/**
 * 知识变更领域事件 / Knowledge-changed domain event.
 *
 * <p>当知识条目发生增删改时发布，供知识图谱等下游消费者自动同步。
 * 这样图谱无需调用方显式 rebuild，避免"知识改了图谱没更新"的不一致。
 * / Published whenever a knowledge entry is added, updated or deleted, so downstream
 * consumers such as the knowledge graph can sync automatically. This avoids the
 * inconsistency of "knowledge changed but graph not updated".
 */
public class KnowledgeChangedEvent extends ApplicationEvent {

    /** 变更类型：ADD | DELETE | BATCH | REINDEX / Change type. */
    public enum ChangeType {
        /** 新增条目 / Entry added. */
        ADD,
        /** 删除条目 / Entry deleted. */
        DELETE,
        /** 批量变更 / Batch changes. */
        BATCH,
        /** 重建索引 / Reindex all. */
        REINDEX
    }

    private final ChangeType changeType;
    private final String entryId;

    /**
     * 构造事件 / Construct the event.
     *
     * @param source     事件源 / Event source.
     * @param changeType 变更类型 / Change type.
     * @param entryId    相关条目 ID（批量时可为空）/ Related entry ID (nullable for batch).
     */
    public KnowledgeChangedEvent(Object source, ChangeType changeType, String entryId) {
        super(source);
        this.changeType = changeType;
        this.entryId = entryId;
    }

    public ChangeType getChangeType() { return changeType; }

    public String getEntryId() { return entryId; }
}
