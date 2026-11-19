# 知识图谱实现细节与改进方案 / Knowledge Graph Implementation & Evolution Plan

> 对应实现版本 / Implementation version: 2026-11-19
> 本文覆盖：定位边界、数据模型、构建流程、查询机制、API、与检索链路的关系、已知限制、改进方案。
> Covers: positioning, data model, build pipeline, query mechanism, APIs, relationship with retrieval, known limitations, and evolution plan.
>
> 相关文档 / Related docs:
> - [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md)：存储、混合检索、双写一致性、Rerank / Storage, hybrid retrieval, dual-write, reranking
> - [DDD_ANALYSIS_MODEL.md](DDD_ANALYSIS_MODEL.md)：分析领域枚举与 period 缺陷修复 / Analysis domain enums and period bug fix

## 1. 定位与核心边界 / Positioning & Core Boundary

知识图谱是**管理页的可视化旁路**，不是 Agent 推理链路的一部分。
The knowledge graph is a **management/visualization sidecar**, not part of the Agent reasoning pipeline.

- 全仓只有 `KnowledgeGraphController` 调用图谱服务；`AnalysisOrchestrator` 从不查询图谱。
  Only `KnowledgeGraphController` calls the graph service; `AnalysisOrchestrator` never queries the graph.
- Agent 链路只调用 `KnowledgeService.retrieveContext()` 做文本检索，并把结果注入 prompt。
  The Agent pipeline only calls `KnowledgeService.retrieveContext()` for textual retrieval and injects results into the prompt.
- 数值计算由 DuckDB 确定性 SQL 承担；知识（含图谱）负责「理解意图与解释结论」。
  Numeric computation is handled by deterministic DuckDB SQL; knowledge (including the graph) handles intent understanding and conclusion explanation.

```
AnalysisController /api/chat
  └─ AnalysisOrchestrator
       ├─ plan(): VectorService + KnowledgeService.retrieveContext → LLM prompt
       ├─ execute(): AnalysisEngine（DuckDB SQL）
       ├─ conclude(): 再次注入 retrieveContext / inject retrieveContext again
       └─ memorize(): addFromAnalysis() → case 条目
                            ↓
              KnowledgeGraphService.rebuild() 【手动触发 / manual only】
                            ↓
              KnowledgeGraphController → 前端 ECharts 力导向图
```

## 2. 数据模型 / Data Model

用两张关系表在 H2/SQLite（元数据同库）中存储图结构，不引入 Neo4j。
Graph structure is stored in two relational tables in H2/SQLite (same DB as metadata); no Neo4j.

```sql
CREATE TABLE IF NOT EXISTS kg_nodes (
    id VARCHAR(128) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    label VARCHAR(255)
);
CREATE TABLE IF NOT EXISTS kg_edges (
    source VARCHAR(128) NOT NULL,
    target VARCHAR(128) NOT NULL,
    relation VARCHAR(32) NOT NULL,
    PRIMARY KEY (source, target, relation)
);
CREATE INDEX IF NOT EXISTS idx_kg_edge_source ON kg_edges(source);
CREATE INDEX IF NOT EXISTS idx_kg_edge_target ON kg_edges(target);
```

来源 / `KnowledgeGraphRepository.java` L38-54。

### 节点与边类型 / Node & Edge Types

| 节点类型 / Node | 来源 / Source | 边 / Edge | 示例 / Example |
|---|---|---|---|
| `knowledge` | 知识条目 / Knowledge entry | — | `knowledge:<entryId>` |
| `dataset` | 条目所属数据集 / Entry's dataset | `belongs_to` | `dataset:<datasetId>` |
| `field` | 指标引用字段 / Referenced field | `references` | `field:销售额` |
| `tag` | 普通标签 / Generic tag | `tagged` | `tag:核心指标` |
| `intent` | `case` 条目的 tag / Tag of case entries | `has_intent` | `intent:trend` |

设计意图 / Design rationale:
- 嵌入式部署零运维，复用现有 JDBC 与事务 / Zero-ops embedded deployment, reuses existing JDBC and transactions.
- 联合主键 `(source, target, relation)` 保证同一三元组唯一，配合「先删后插」实现幂等写入 / Composite PK keeps triples unique; delete-then-insert makes writes idempotent.
- `intent` 节点来自 `case` 条目的 `tag`，其值由 `AnalysisIntent.getValue()` 生成 / Intent nodes come from case-entry tags produced by `AnalysisIntent.getValue()`.

## 3. 构建流程 / Build Pipeline

`KnowledgeGraphService.rebuild()` 是**全量清空重建**：`clear()` → `listAll()` → 逐条抽取。

`KnowledgeGraphService.rebuild()` performs a **full clear-and-rebuild**: `clear()` → `listAll()` → per-entry extraction.

```39:78:backend/src/main/java/com/dataagent/service/KnowledgeGraphService.java
public Map<String, Object> rebuild() {
    graphRepo.clear();
    List<KnowledgeEntry> entries = knowledgeRepo.listAll();
    for (KnowledgeEntry e : entries) {
        String kid = "knowledge:" + e.getId();
        graphRepo.addNode(kid, "knowledge", e.getTitle());
        // dataset / field / tag / intent 抽取 ...
    }
    return graphRepo.stats();
}
```

抽取规则 / Extraction rules:
1. 每条知识建 `knowledge:<id>` 节点 / One `knowledge:<id>` node per entry.
2. 有 `datasetId` → `dataset:<id>` 节点 + `belongs_to` 边 / If datasetId exists, add dataset node and edge.
3. 遍历 `fields` → `field:<字段名>` 节点 + `references` 边 / One field node + edge per field.
4. 遍历 `tags` → `kind=case` 视为 `intent:<tag>` + `has_intent`，否则 `tag:<tag>` + `tagged`。
   Case tags become intents with `has_intent`; others become tags with `tagged`.

**关键限制**：`KnowledgeService.add()/delete()` 不触碰 `kg_*` 表；图谱不会自动同步，必须手动调用 `POST /api/knowledge/graph/rebuild`。
**Key limitation**: `KnowledgeService.add()/delete()` never touch `kg_*` tables; the graph does not auto-sync and requires a manual rebuild call.

> 实测发现 / Observed in local run (2026-11-19)：知识条目 `5190d44a-...`（"有多少订单？"）存在于知识库但不在图中，其邻居查询返回空；当前图共 17 节点 / 18 边。这正是上述陈旧性问题的直接证据。
> Knowledge entry `5190d44a-...` exists in the knowledge base but is absent from the graph, and its neighbor query returns empty; the graph has 17 nodes / 18 edges. This is direct evidence of the staleness issue.

## 4. 查询机制 / Query Mechanism

### 4.1 无向 BFS / Undirected BFS

`KnowledgeGraphRepository.neighbors()`（L94-113）：`LinkedHashSet` 去重，每跳同时查出边与入边，`frontier` 为空提前终止。
`LinkedHashSet` for dedup; each hop queries both outgoing and incoming edges; early exit when frontier is empty.

```94:113:backend/src/main/java/com/dataagent/repository/KnowledgeGraphRepository.java
public Set<String> neighbors(String startId, int hops) {
    Set<String> visited = new LinkedHashSet<>();
    visited.add(startId);
    List<String> frontier = new ArrayList<>(List.of(startId));
    for (int h = 0; h < Math.max(0, hops); h++) {
        List<String> next = new ArrayList<>();
        for (String node : frontier) {
            List<String> outs = jdbc.queryForList("SELECT target FROM kg_edges WHERE source = ?", String.class, node);
            List<String> ins  = jdbc.queryForList("SELECT source FROM kg_edges WHERE target = ?", String.class, node);
            for (String n : outs) if (visited.add(n)) next.add(n);
            for (String n : ins)  if (visited.add(n)) next.add(n);
        }
        frontier = next;
        if (frontier.isEmpty()) break;
    }
    return visited;
}
```

设计要点 / Design notes:
- 无向遍历：`references`/`belongs_to` 是有向写入，但「哪些指标共享字段」需要反向查找 / Undirected traversal: edges are written one-way, but "which metrics share a field" requires reverse lookup.
- `hops` 在 Service 层钳制为 1~5，防止参数放大导致指数级扩散 / Clamped to 1–5 at service layer to prevent exponential fan-out.

### 4.2 子图组装 / Subgraph Assembly

`KnowledgeGraphService.neighbors()`（L110-124）拿到 ID 集合后，在 Java 层过滤 `listNodes()` / `listEdges()`，返回 `{center, hops, nodes, edges}`。
After getting the ID set, Java filters `listNodes()` / `listEdges()` in memory and returns `{center, hops, nodes, edges}`.

**兼容性风险 / Compatibility risk**：该方法硬编码 `n.get("ID")`、`e.get("SOURCE")` 等大写键；H2 返回大写列名，SQLite 返回小写列名。切换 `METADATA_TYPE=sqlite` 时子图可能为空。而 `KnowledgeService.mapValue()`（L415-422）已实现大小写兼容，可复用。
The method hardcodes uppercase keys; H2 returns uppercase column names while SQLite returns lowercase, so the subgraph may be empty under `METADATA_TYPE=sqlite`. `KnowledgeService.mapValue()` already handles this and can be reused.

## 5. API 与前端可视化 / APIs & Frontend Visualization

### 5.1 REST API

`KnowledgeGraphController`，基础路径 `/api/knowledge/graph`：

| 方法 / Method | 路径 / Path | 说明 / Description |
|---|---|---|
| POST | `/rebuild` | 全量重建图谱 / Full rebuild |
| GET | `` (root) | 完整图：nodes + edges + stats / Full graph |
| GET | `/nodes` | 全部节点 / All nodes |
| GET | `/edges` | 全部边 / All edges |
| GET | `/neighbors?nodeId=&hops=` | N 跳邻居子图（hops 钳制 1~5）/ N-hop subgraph |
| GET | `/stats` | 节点/边统计与按类型分组 / Node/edge counts by type |

### 5.2 前端 / Frontend

- `components/KnowledgeGraphView.tsx`：ECharts `type:'graph', layout:'force'`（`repulsion:260, edgeLength:90`），支持拖拽缩放、边标签、选节点查 2 跳邻居。
- 兼容 H2 大写键（`n.ID/n.TYPE/n.LABEL`、`e.SOURCE/e.TARGET/e.RELATION`）与 SQLite 小驼峰键。
- 通过 `KnowledgePanel.tsx` 三个 Tab（知识条目 / 知识图谱 / Zvec 数据）集成，入口 `api.ts` L42-46。
- 技术债：图谱数据无专用 TS 类型，使用 `Record<string, unknown>` + `any`，建议补充类型定义。
  Tech debt: no dedicated TS types for graph data; `Record<string, unknown>` + `any` is used.

## 6. 与检索链路的关系 / Relationship with Retrieval Pipeline

图谱与混合检索是**两条独立链路**，不要混淆：
The graph and hybrid retrieval are **two independent pipelines**:

```
检索链路 / Retrieval:
  query → Zvec MultiQuery（HNSW 向量 + jieba FTS 双路）
        → native setRerankRrf(1)  → filter(kind, datasetId)
        → Java Rerank（rule 0.7/0.3 或 LLM 0~1）→ topK
        → 失败降级：knowledge_terms 倒排（中文 bigram）+ 子串扫描
        → 注入 Plan / Conclude prompt

图谱链路 / Graph:
  KnowledgeEntry → rebuild() → kg_nodes/kg_edges → BFS → 前端可视化
  （当前不参与 prompt 注入 / currently not injected into prompts）
```

RRF 公式 / Formula：`score(d) = Σ 1/(60 + rank_r(d))`，只用排名倒数，不依赖各召回分支的分数尺度。
Only reciprocal ranks are used; independent of the score scales of each recall branch.

## 7. 已知限制 / Known Limitations

| # | 问题 / Issue | 证据 / Evidence |
|---|---|---|
| 1 | 图谱不与知识条目自动同步，手动全量重建 | `rebuild()` 无自动触发；实测陈旧节点 |
| 2 | SQLite 模式下邻居子图可能为空（大小写键） | Service 硬编码大写键 vs JDBC 列名差异 |
| 3 | 无图算法、无图谱推理（社区发现/最短路径/PageRank） | `KNOWLEDGE_BASE.md` Roadmap L245 |
| 4 | `listNodes()/listEdges()` 全表加载后内存过滤，规模受限 | `neighbors()` 实现 |
| 5 | 无权限隔离、无版本管理、无软删除 | Roadmap L242-244 |
| 6 | 前端图谱数据缺少专用类型定义 | `KnowledgeGraphView.tsx` 使用 `any` |

## 8. 改进方案 / Evolution Plan

### 阶段一：一致性与兼容性 / Phase 1: Consistency & Compatibility
1. **增量同步 / Incremental sync**：`KnowledgeService.add()/delete()` 后发领域事件，增量 upsert 图谱；失败条目进 outbox 重试。
2. **对账机制 / Reconciliation**：版本水位 + 定时全量 `rebuild()` 对账，复用知识库 `pending/vector_failed/ready` 状态机。
3. **修复大小写兼容 / Fix case sensitivity**：复用 `KnowledgeService.mapValue()`，或在 SQL 中统一 `AS id/source/target` 别名。
4. **补类型 / Add types**：为前端图谱节点/边定义 TypeScript 接口，去掉 `any`。

### 阶段二：GraphRAG 增强 / Phase 2: GraphRAG Enhancement
1. 检索 top 实体的 1~2 跳子图，序列化为「指标 → 字段 → 关联指标」路径文本，与业务知识一起注入 Plan prompt。
   Retrieve 1–2 hop subgraphs of top entities, serialize metric→field→metric paths, and inject into the Plan prompt.
2. Conclude 时用图路径解释结论依据，提升可解释性 / Use graph paths to explain conclusions.
3. 控制注入 token 量（限定节点数/跳数），并保证图谱与知识条目实时一致，避免注入错误口径。
   Cap injected tokens (node/hop limits) and keep the graph in sync to avoid injecting stale metric definitions.

### 阶段三：图算法与规模化 / Phase 3: Graph Algorithms & Scale
1. PageRank 做节点重要度、社区发现做指标聚类、最短路径做关联解释。
2. 规模上来后用递归 CTE 在库内完成 BFS，或迁移 Neo4j/JanusGraph；前端可视化加分页/采样。
3. 增加租户维度与 `deleted` 标记，按知识条目可见性过滤图谱。

## 9. 关键源码索引 / Source Code Index

| 文件 / File | 职责 / Responsibility |
|---|---|
| `backend/.../controller/KnowledgeGraphController.java` | REST 入口 / REST endpoints |
| `backend/.../service/KnowledgeGraphService.java` | 抽取与子图组装 / Extraction & subgraph assembly |
| `backend/.../repository/KnowledgeGraphRepository.java` | 表结构与 BFS / Schema & BFS |
| `backend/.../service/KnowledgeService.java` | 知识门面、retrieveContext、mapValue / Facade & compatibility |
| `backend/.../service/KnowledgeVectorStore.java` | 混合检索、RRF、filter / Hybrid search & RRF |
| `backend/.../service/AnalysisOrchestrator.java` | Plan/Execute/Conclude/Memorize 编排 |
| `frontend/src/components/KnowledgeGraphView.tsx` | ECharts 力导向图可视化 |
| `frontend/src/components/KnowledgePanel.tsx` | 知识库三 Tab 容器 |
