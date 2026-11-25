package com.axiom.service;

import com.axiom.domain.KnowledgeChangedEvent;
import com.axiom.model.KnowledgeEntry;
import com.axiom.repository.KnowledgeGraphRepository;
import com.axiom.repository.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 知识图谱服务 / Knowledge graph service.
 *
 * <p>从已有知识条目中抽取实体与关系，构建轻量图谱：
 * Builds a lightweight graph by extracting entities and relations from existing knowledge:
 * <ul>
 *   <li>知识条目 / Knowledge entry</li>
 *   <li>数据集（条目所属）/ Dataset an entry belongs to</li>
 *   <li>字段（指标引用的字段）/ Fields referenced by a metric</li>
 *   <li>标签与意图 / Tags and intents</li>
 * </ul>
 * 图谱让「哪些指标依赖同一字段」「某数据集关联哪些知识」这类查询变得直观。
 * The graph makes queries like "which metrics share a field" intuitive.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final KnowledgeGraphRepository graphRepo;
    private final KnowledgeRepository knowledgeRepo;
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    /**
     * 从知识条目重建图谱 / Rebuild graph from knowledge entries.
     *
     * @return 构建统计 / Build statistics.
     */
    public Map<String, Object> rebuild() {
        // 全量重建与增量同步互斥：clear 进行中混入增量事件会被 clear 抹掉或产生半图。
        // / Full rebuild is mutually exclusive with incremental sync: events landing inside
        // a clear() would be erased or produce a half-built graph.
        synchronized (graphLock) {
            // clear + 逐条写入 + bumpVersion 包在一个事务里：中途失败整体回滚，
            // 不会留下被清空的半图，版本号也不前进（快照缓存保持一致）。
            // / clear + per-entry writes + bumpVersion run in one transaction: a mid-way
            // failure rolls back instead of leaving a half-built graph, and the version
            // stays put so the snapshot cache remains consistent.
            txTemplate.executeWithoutResult(status -> {
                graphRepo.clear();
                for (KnowledgeEntry e : knowledgeRepo.listAll()) {
                    buildEntryGraph(e);
                }
                graphRepo.bumpVersion();
            });
        }
        Map<String, Object> stats = graphRepo.stats();
        log.info("[KnowledgeGraph] rebuilt: {}", stats);
        return stats;
    }

    /**
     * 为单条知识写入图谱子图（增量与全量共用）/ Write one entry's subgraph (shared by incremental and full rebuild).
     *
     * @param e 知识条目 / Knowledge entry.
     */
    private void buildEntryGraph(KnowledgeEntry e) {
        String kid = "knowledge:" + e.getId();
        graphRepo.addNode(kid, "knowledge", e.getTitle());

        // knowledge -> dataset / 知识所属数据集.
        if (e.getDatasetId() != null && !e.getDatasetId().isBlank()) {
            String did = "dataset:" + e.getDatasetId();
            graphRepo.addNode(did, "dataset", e.getDatasetId());
            graphRepo.addEdge(kid, did, "belongs_to");
        }

        // knowledge -> field / 指标引用的字段.
        if (e.getFields() != null) {
            for (String field : e.getFields()) {
                if (field == null || field.isBlank()) continue;
                String fid = "field:" + field.trim();
                graphRepo.addNode(fid, "field", field.trim());
                graphRepo.addEdge(kid, fid, "references");
            }
        }

        // knowledge -> tag / 标签；case 的 tag 视为意图 / Tags; case tags are intents.
        if (e.getTags() != null) {
            for (String tag : e.getTags()) {
                if (tag == null || tag.isBlank()) continue;
                boolean isIntent = "case".equals(e.getKind());
                String tid = (isIntent ? "intent:" : "tag:") + tag.trim();
                graphRepo.addNode(tid, isIntent ? "intent" : "tag", tag.trim());
                graphRepo.addEdge(kid, tid, isIntent ? "has_intent" : "tagged");
            }
        }
    }

    /**
     * 监听知识变更事件，按受影响 ID 增量同步图谱 / Incrementally sync graph by affected IDs.
     *
     * <p>所有变更类型均按 ID 列表增量处理，不再全量重建：
     * ADD/BATCH 先删旧子图再写新；DELETE/DELETE_BATCH 仅删子图；最后清理孤立节点。
     * All change types are processed incrementally by ID list (no full rebuild):
     * ADD/BATCH remove then rewrite; DELETE variants only remove; orphan nodes are pruned.
     *
     * @param event 知识变更事件 / Knowledge-change event.
     */
    /**
     * 图谱写操作串行锁 / Serializes graph writes.
     *
     * <p>事件来自不同请求线程，并发增量同步会出现两类损坏：
     * 1) delete→restore 乱序导致节点丢失；2) A 加节点未连边时 B 清理孤儿节点，
     * 随后 A 连边产生悬空边。事件监听与 rebuild 都必须持锁串行执行。
     * / Concurrent incremental syncs corrupt the graph: out-of-order delete/restore loses
     * nodes, and orphan-pruning between a node insert and its edges creates dangling edges.
     * Both event handling and rebuild must hold this lock.
     */
    private final Object graphLock = new Object();

    @EventListener
    public void onKnowledgeChanged(KnowledgeChangedEvent event) {
        var type = event.getChangeType();
        java.util.List<String> ids = event.getEntryIds();
        if (ids.isEmpty()) {
            log.debug("[KnowledgeGraph] empty change event, skip: type={}", type);
            return;
        }
        log.info("[KnowledgeGraph] incremental sync: type={} ids={}", type, ids.size());

        boolean isAdd = type == KnowledgeChangedEvent.ChangeType.ADD
                || type == KnowledgeChangedEvent.ChangeType.BATCH;

        synchronized (graphLock) {
            for (String entryId : ids) {
                // 先删旧子图保证幂等（ADD 覆盖更新、DELETE 纯删除）/ Remove old subgraph first for idempotency.
                graphRepo.deleteEntrySubgraph(entryId);
                if (isAdd) {
                    KnowledgeEntry entry = knowledgeRepo.findById(entryId);
                    if (entry != null) buildEntryGraph(entry);
                }
            }
            graphRepo.deleteOrphanNodes();
            graphRepo.bumpVersion();
        }
        log.info("[KnowledgeGraph] incremental sync done: type={} affected={}", type, ids.size());
    }

    /** 列出全部节点 / List all nodes. */
    public Map<String, Object> nodes() {
        return Map.of("nodes", graphRepo.listNodes());
    }

    /** 列出全部边 / List all edges. */
    public Map<String, Object> edges() {
        return Map.of("edges", graphRepo.listEdges());
    }

    /**
     * 完整图数据（供前端可视化）/ Full graph data for frontend visualization.
     *
     * @return 节点、边和统计 / Nodes, edges and stats.
     */
    public Map<String, Object> graph() {
        Map<String, Object> m = new HashMap<>();
        m.put("nodes", graphRepo.listNodes());
        m.put("edges", graphRepo.listEdges());
        m.put("stats", graphRepo.stats());
        return m;
    }

    /**
     * N 跳邻居查询 / N-hop neighbor query.
     *
     * @param nodeId 起始节点 ID / Start node ID.
     * @param hops   跳数 / Hop count.
     * @return 邻居节点及连接边 / Neighbor nodes and connecting edges.
     */
    public Map<String, Object> neighbors(String nodeId, int hops) {
        // 先钳制再使用并回传，避免响应里的 hops 与实际计算跳数不符。
        // Clamp before both use and response so the reported hops matches the actual traversal.
        int effectiveHops = Math.max(1, Math.min(hops, 5));
        Set<String> ids = graphRepo.neighbors(nodeId, effectiveHops);
        // 子图过滤下推 SQL：按 ID 集合分批取节点与内部边，不再全表拉取后内存过滤
        // （图大时代价从 O(全图) 降到 O(子图)）。/ Push subgraph filtering down to SQL:
        // fetch nodes and internal edges by ID set instead of loading the whole graph.
        List<Map<String, Object>> subNodes = graphRepo.nodesByIds(ids);
        List<Map<String, Object>> subEdges = graphRepo.edgesWithinSubgraph(ids);
        return Map.of("center", nodeId, "hops", effectiveHops, "nodes", subNodes, "edges", subEdges);
    }

}
