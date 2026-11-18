package com.dataagent.service;

import com.dataagent.model.KnowledgeEntry;
import com.dataagent.repository.KnowledgeGraphRepository;
import com.dataagent.repository.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        List<KnowledgeEntry> entries = knowledgeRepo.listAll();

        for (KnowledgeEntry e : entries) {
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
        Map<String, Object> stats = graphRepo.stats();
        log.info("[KnowledgeGraph] rebuilt: {}", stats);
        return stats;
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
            if (ids.contains(String.valueOf(n.get("ID")))) subNodes.add(n);
        }
        List<Map<String, Object>> subEdges = new ArrayList<>();
        for (Map<String, Object> e : graphRepo.listEdges()) {
            if (ids.contains(String.valueOf(e.get("SOURCE"))) && ids.contains(String.valueOf(e.get("TARGET")))) {
                subEdges.add(e);
            }
        }
        return Map.of("center", nodeId, "hops", hops, "nodes", subNodes, "edges", subEdges);
    }

    /** 图统计 / Graph statistics. */
    public Map<String, Object> stats() { return graphRepo.stats(); }
}
