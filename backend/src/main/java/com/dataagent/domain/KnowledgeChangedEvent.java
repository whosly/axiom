package com.dataagent.domain;

import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 知识变更领域事件 / Knowledge-changed domain event.
 *
 * <p>当知识条目发生增删改时发布，携带受影响的条目 ID 列表，供知识图谱等下游增量同步。
 * 单条变更用单元素列表，批量用完整列表；REINDEX 向量重建不改变图谱结构，故不发事件。
 * Published on knowledge changes with affected entry IDs so downstream consumers
 * (e.g. knowledge graph) can sync incrementally. REINDEX emits no event because
 * vector rebuilds do not change graph structure.
 */
public class KnowledgeChangedEvent extends ApplicationEvent {

    /** 变更类型 / Change type. */
    public enum ChangeType {
        /** 新增/更新条目（增量）/ Entry added or updated (incremental). */
        ADD,
        /** 删除单条（增量）/ Single entry deleted (incremental). */
        DELETE,
        /** 批量新增（按 ID 列表增量）/ Batch added (incremental by ID list). */
        BATCH,
        /** 批量删除（按 ID 列表增量）/ Batch deleted (incremental by ID list). */
        DELETE_BATCH
    }

    private final ChangeType changeType;
    /** 受影响的条目 ID（单条变更含 1 个，批量含多个）/ Affected entry IDs. */
    private final List<String> entryIds;

    /**
     * 构造事件 / Construct the event.
     *
     * @param source    事件源 / Event source.
     * @param type      变更类型 / Change type.
     * @param entryIds  受影响 ID 列表 / Affected entry IDs.
     */
    public KnowledgeChangedEvent(Object source, ChangeType type, List<String> entryIds) {
        super(source);
        this.changeType = type;
        this.entryIds = entryIds == null ? List.of() : List.copyOf(entryIds);
    }

    /** 单条变更便捷构造 / Convenience constructor for a single entry. */
    public KnowledgeChangedEvent(Object source, ChangeType type, String entryId) {
        this(source, type, entryId == null ? List.of() : List.of(entryId));
    }

    public ChangeType getChangeType() { return changeType; }

    public List<String> getEntryIds() { return entryIds; }

    /** 首个 ID（单条变更）/ First ID (for single-entry changes). */
    public String getEntryId() { return entryIds.isEmpty() ? null : entryIds.get(0); }
}
