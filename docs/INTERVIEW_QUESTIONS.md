# 面试题与参考答案 / Interview Questions & Reference Answers

> 基于「数据分析 Agent」项目 / Based on the Data Analysis Agent project
> 覆盖：架构设计、知识图谱、混合检索、一致性、降级、工程实践、前端可视化。
> Covers: architecture, knowledge graph, hybrid retrieval, consistency, fallback, engineering practices, frontend.
>
> 相关文档 / Related docs: [KNOWLEDGE_BASE.md](KNOWLEDGE_BASE.md)、[KNOWLEDGE_GRAPH.md](KNOWLEDGE_GRAPH.md)、[DDD_ANALYSIS_MODEL.md](DDD_ANALYSIS_MODEL.md)

## 目录 / Table of Contents

1. 为什么用两张关系表而不是 Neo4j？
2. `kg_edges` 为什么用 `(source, target, relation)` 联合主键？
3. 图谱重建是手动全量的，生产环境如何保证一致性？
4. RRF 为什么比直接加权分数更鲁棒？公式里 60 是什么？
5. 中文为什么用 bigram 分词，和 Zvec 的 jieba FTS 是否重复？
6. "无 API Key 也能完整运行"的准确含义？
7. 双写一致性状态机怎么设计？为什么不能先写向量再写元数据？
8. BFS 邻居查询为什么同时查入边和出边？hops 为什么要钳制？
9. `KnowledgeEntry` 如何统一不同知识来源？
10. 图谱里的 `intent` 节点从哪来？
11. 切到 SQLite 会有什么隐患？如何发现这类问题？
12. 如果让你设计 GraphRAG 增强，会怎么做？
13. 前端力导向图是怎么实现的？有哪些兼容处理？

---

## 1. 为什么用两张关系表而不是 Neo4j？ / Why two relational tables instead of Neo4j?

**答案 / Answer**：
本项目是嵌入式部署（单个可执行 jar、H2/SQLite 元数据），指标-字段-数据集的关联是浅层星形结构，深度通常不超过 2 跳。两张关系表零运维、复用现有 JDBC 与事务，足以表达这类关系，代价是没有原生图算法和高效多跳遍历。

The project uses embedded deployment (single jar, H2/SQLite). Metric–field–dataset relations form shallow star-shaped graphs, usually within 2 hops. Two relational tables need zero ops and reuse existing JDBC/transactions. The trade-off is no native graph algorithms or efficient multi-hop traversal.

**延伸 / Follow-up**：什么时候该迁 Neo4j？万级以上节点、多跳查询频繁、需要路径/社区算法时。
Migrate to Neo4j when node count reaches tens of thousands, multi-hop queries are frequent, or path/community algorithms are needed.

## 2. `kg_edges` 为什么用 `(source, target, relation)` 联合主键？ / Why a composite PK?

**答案 / Answer**：
同一对节点之间可能存在多种关系（既是 `references` 又是 `tagged`）。联合主键保证同一三元组唯一，配合「先 DELETE 后 INSERT」写入实现幂等，使重复 rebuild 不产生重复边，也避免脏数据累积。

Multiple relations can exist between the same pair of nodes (e.g. both `references` and `tagged`). The composite PK keeps each triple unique; combined with delete-then-insert writes, rebuilds become idempotent without accumulating duplicate edges.

## 3. 图谱重建是手动全量的，生产环境如何保证一致性？ / How to keep the graph consistent in production?

**答案 / Answer**：
现状是 `clear()` + 全表扫描重建，新增/删除知识不会触发，存在陈旧窗口（本地实测：新增 case 条目未出现在图谱中）。

Current behavior is `clear()` + full scan; knowledge additions/deletions don't trigger it, leaving a staleness window (locally observed: a new case entry was missing from the graph).

改进方向 / Improvements:
- 写路径发领域事件做增量 upsert / Emit domain events on the write path for incremental upserts；
- 失败条目进 outbox 重试 / Failed entries go to an outbox for retry；
- 版本水位 + 定时全量 rebuild 对账 / Version watermark + scheduled full rebuild for reconciliation；
- 复用知识库 `pending/vector_failed/ready` 双写状态机与 `/reindex` 修复机制。
  Reuse the knowledge base's dual-write state machine and reindex endpoint.

## 4. RRF 为什么比直接加权分数更鲁棒？公式里 60 是什么？ / Why RRF? What is 60?

**答案 / Answer**：
向量相似度和 BM25/FTS 分数尺度不同，直接加权需要归一化且容易被某一路主导；RRF 只用排名倒数 `1/(60+rank)`，与分数尺度无关，更鲁棒。60 是平滑常数（经验值），压缩头部排名差距，避免第 1 名权重过大。

Vector similarity and BM25/FTS scores are on different scales; direct weighting needs normalization and can be dominated by one branch. RRF uses only reciprocal ranks `1/(60+rank)`, independent of score scales. 60 is a smoothing constant that compresses top-rank differences.

**注意 / Note**：本项目 RRF 在 Zvec native 层通过 `setRerankRrf(1)` 完成；Java 层 Rerank 是召回后的二次精排（规则 0.7/0.3 或 LLM 0~1），两者职责不同。
RRF runs in Zvec native via `setRerankRrf(1)`; Java-layer rerank is a second-stage refinement.

## 5. 中文为什么用 bigram 分词，和 Zvec 的 jieba FTS 是否重复？ / bigram vs jieba FTS?

**答案 / Answer**：
bigram 无词典、零依赖，对「华东大区」这类业务词有不错召回，且能在 H2/SQLite 纯 SQL 降级场景中工作；jieba 只在 Zvec 可用时生效。两者是主链路与降级链路的关系，不是重复建设。

Bigrams are dictionary-free and zero-dependency, give decent recall for business terms like "华东大区", and work in pure-SQL fallback on H2/SQLite. jieba only works when Zvec is available. They are primary and fallback paths, not redundant.

## 6. "无 API Key 也能完整运行"的准确含义？ / What does "fully usable without API keys" mean?

**答案 / Answer**：
- 默认 embedding 是本地 ONNX（bge-small-zh-v1.5），不需要 Key；
- 无 `DEEPSEEK_API_KEY` 时 `llmAvailable()=false`：Planner 降级规则引擎、结论用模板、rerank 走 `ruleRerank`；
- 只有显式配 `embedding.provider=api` 且无 Key 时向量写入失败（`vector_failed`），检索自动走倒排索引。

Default embedding is local ONNX (no key needed). Without `DEEPSEEK_API_KEY`, the planner falls back to rules, conclusions use templates, and rerank uses `ruleRerank`. Only when `embedding.provider=api` is explicitly set without a key does vector writing fail, and retrieval then falls back to the inverted index.

## 7. 双写一致性状态机怎么设计？为什么不能先写向量再写元数据？ / Dual-write state machine?

**答案 / Answer**：
状态机：`pending → ready`，失败 `pending → vector_failed → reindex → pending → ready`。
必须**先写元数据（带 status）再写向量**：即便向量失败也有记录可扫描重建；反过来先写向量再写元数据，元数据失败会留下孤儿向量且无从发现。`GET /api/knowledge/status` 监控，`/reindex?force=` 修复。

Write metadata (with status) **before** vectors: if vector writing fails, a record remains for rebuild. The reverse order leaves orphan vectors with no discovery path when metadata writing fails.

## 8. BFS 邻居查询为什么同时查入边和出边？hops 为什么要钳制？ / Undirected BFS & hop clamping?

**答案 / Answer**：
`references`/`belongs_to` 是有向写入，但「哪些指标共享字段」需要从 field 反向找 knowledge，所以遍历视为无向。hops 钳制 1~5 防止极端参数导致指数级扩散、拖垮嵌入式数据库。

Edges are written one-way, but "which metrics share a field" requires reverse lookup from field to knowledge, so traversal is undirected. Hop clamping (1–5) prevents exponential fan-out that could overwhelm the embedded database.

## 9. `KnowledgeEntry` 如何统一不同知识来源？ / How does KnowledgeEntry unify sources?

**答案 / Answer**：
手动录入（metric/glossary/recipe）、分析沉淀（case）、未来文档解析统一为同一结构：`kind` 区分类型，`fields/tags` 存 JSON，`datasetId` 为空表示全局，`textHash`（SHA-256）做幂等去重，`formula` 存确定性规则直接执行。新增来源只需加适配器，存储与检索层不重复开发。

All sources normalize into one structure: `kind` for type, `fields/tags` as JSON, empty `datasetId` for global knowledge, `textHash` (SHA-256) for idempotent dedup, `formula` for deterministic execution. New sources only need an adapter.

## 10. 图谱里的 `intent` 节点从哪来？ / Where do intent nodes come from?

**答案 / Answer**：
来自 `kind=case` 的知识条目，其 `tag` 由 `AnalysisIntent.getValue()` 生成（如 `trend`、`compare`）。`rebuild()` 中 case 的 tag 前缀为 `intent:` 并用 `has_intent` 边连接；其他类型的 tag 用 `tag:` + `tagged` 边。

They come from case entries whose tags are produced by `AnalysisIntent.getValue()` (e.g. `trend`, `compare`). In `rebuild()`, case tags get the `intent:` prefix and `has_intent` edges; other tags use `tag:` and `tagged`.

## 11. 切到 SQLite 会有什么隐患？如何发现这类问题？ / SQLite pitfalls?

**答案 / Answer**：
H2 的 `queryForList` 返回大写列名（`ID/SOURCE/TARGET`），SQLite 返回小写。图谱服务硬编码大写键取值，`METADATA_TYPE=sqlite` 时 N 跳子图可能为空；而 `KnowledgeService.mapValue()` 已处理此差异。

H2 returns uppercase column names while SQLite returns lowercase. The graph service hardcodes uppercase keys, so the N-hop subgraph may be empty under SQLite; `KnowledgeService.mapValue()` already handles this.

**发现方式 / Detection**：配置矩阵测试（H2/SQLite × 各 API），代码评审关注 Map 键访问，以及用 SQL 别名统一列名（`AS id/source/target`）。
Configuration matrix testing, code review of Map key access, and unified SQL column aliases.

## 12. 如果让你设计 GraphRAG 增强，会怎么做？ / How would you design GraphRAG?

**答案 / Answer**：
1. 检索阶段取 top 实体的 1~2 跳子图，把「指标 GMV → 字段 销售额/成本 → 关联指标 客单价」序列化为文本，与业务知识一起注入 Plan prompt；
2. Conclude 时用图路径解释结论依据；
3. 控制 token 量（限定节点数/跳数），并保证图谱与知识条目实时一致，避免注入错误口径。

Retrieve 1–2 hop subgraphs of top entities, serialize paths like "GMV → 销售额/成本 → 客单价" into text for the Plan prompt; use graph paths to explain conclusions; cap tokens and keep the graph in sync to avoid stale metric definitions.

## 13. 前端力导向图是怎么实现的？有哪些兼容处理？ / Frontend force-directed graph?

**答案 / Answer**：
`KnowledgeGraphView.tsx` 用 ECharts `type:'graph', layout:'force'`（`repulsion:260, edgeLength:90`），支持拖拽缩放、边标签显示、选节点查 2 跳邻居。兼容处理：同时读取 H2 大写键与 SQLite 小驼峰键；通过 `KnowledgePanel.tsx` 的三个 Tab 集成（知识条目/知识图谱/Zvec 数据）。

Uses ECharts `type:'graph', layout:'force'` with drag/zoom, edge labels, and 2-hop neighbor lookup on node selection. It reads both H2 uppercase keys and SQLite camelCase keys, and is integrated via the three tabs in `KnowledgePanel.tsx`.

**技术债 / Tech debt**：图谱数据无专用 TS 类型，使用 `Record<string, unknown>` + `any`，建议补充节点/边接口定义。
No dedicated TS types for graph data; adding node/edge interfaces is recommended.
