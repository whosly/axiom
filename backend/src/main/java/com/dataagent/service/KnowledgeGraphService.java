package com.dataagent.service;

import com.dataagent.domain.KnowledgeChangedEvent;
import com.dataagent.model.KnowledgeEntry;
import com.dataagent.repository.KnowledgeGraphRepository;
import com.dataagent.repository.KnowledgeRepository;
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

    /**
     * 从知识条目重建图谱 / Rebuild graph from knowledge entries.
     *
     * @return 构建统计 / Build statistics.
     */
    public Map<String, Object> rebuild() {
        graphRepo.clear();
        for (KnowledgeEntry e : knowledgeRepo.listAll()) {
            buildEntryGraph(e);
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
     * 监听知识变更事件，自动重建图谱 / Auto-rebuild graph on knowledge-change events.
     *
     * <p>知识条目增删改时由 KnowledgeService 发布事件，这里自动同步图谱。
     * ADD/DELETE 按 entryId 增量更新（删旧子图再写新子图），BATCH/REINDEX 走全量重建。
     * / KnowledgeService publishes events on entry changes; ADD/DELETE sync incrementally
     * by entryId (remove old subgraph then write new), while BATCH/REINDEX do a full rebuild.
     *
     * @param event 知识变更事件 / Knowledge-change event.
     */
    @EventListener
    public void onKnowledgeChanged(KnowledgeChangedEvent event) {
        log.info("[KnowledgeGraph] auto-sync triggered: type={} entryId={}",
                event.getChangeType(), event.getEntryId());
        var type = event.getChangeType();
        String entryId = event.getEntryId();

        // 批量与重建：全量更划算 / Batch and reindex are cheaper as a full rebuild.
        if (type == KnowledgeChangedEvent.ChangeType.BATCH
                || type == KnowledgeChangedEvent.ChangeType.REINDEX
                || entryId == null || entryId.isBlank()) {
            rebuild();
            return;
        }

        // 增量：先删旧子图，ADD 时再从元数据读取最新条目重建
        // Incremental: remove the old subgraph, then rebuild from metadata for ADD.
        graphRepo.deleteEntrySubgraph(entryId);
        if (type == KnowledgeChangedEvent.ChangeType.ADD) {
            KnowledgeEntry entry = knowledgeRepo.findById(entryId);
            if (entry != null) buildEntryGraph(entry);
        }
        // 清理因删除而孤立的 field/tag 节点 / Clean up orphaned field/tag nodes.
        graphRepo.deleteOrphanNodes();
        log.info("[KnowledgeGraph] incremental sync done: type={} entryId={}", type, entryId);
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
        Set<String> ids = graphRepo.neighbors(nodeId, Math.max(1, Math.min(hops, 5)));
        List<Map<String, Object>> allNodes = graphRepo.listNodes();
        List<Map<String, Object>> subNodes = new ArrayList<>();
        for (Map<String, Object> n : allNodes) {
            // 大小写兼容：H2 返回大写键（ID），SQLite 返回小写键（id）
            // Case-insensitive: H2 returns uppercase keys (ID), SQLite returns lowercase (id).
            if (ids.contains(String.valueOf(mapValue(n, "id")))) subNodes.add(n);
        }
        List<Map<String, Object>> subEdges = new ArrayList<>();
        for (Map<String, Object> e : graphRepo.listEdges()) {
            String source = String.valueOf(mapValue(e, "source"));
            String target = String.valueOf(mapValue(e, "target"));
            if (ids.contains(source) && ids.contains(target)) subEdges.add(e);
        }
        return Map.of("center", nodeId, "hops", hops, "nodes", subNodes, "edges", subEdges);
    }

    /**
     * 不区分大小写读取 Map 值 / Case-insensitive map lookup.
     *
     * <p>JdbcTemplate.queryForList 在 H2 下返回大写列名，SQLite 下返回小写列名；
     * 统一用此方法读取，保证两种元数据模式下邻居查询行为一致。
     * / JdbcTemplate returns uppercase column keys on H2 and lowercase on SQLite;
     * this helper normalizes access so neighbor queries behave identically in both modes.
     */
    private Object mapValue(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /** 图统计 / Graph statistics. */
    public Map<String, Object> stats() { return graphRepo.stats(); }
}
