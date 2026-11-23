# 架构设计 / Architecture

> 本文描述 Axiom 的分析领域模型、知识库、知识图谱与存储布局。实现版本：2026-11-23。

## 目录

- [1. 总体设计](#1-总体设计)
- [2. 分析领域模型](#2-分析领域模型)
- [3. 知识库](#3-知识库)
- [4. 知识图谱](#4-知识图谱)
- [5. 存储与数据布局](#5-存储与数据布局)
- [6. 设计决策问答](#6-设计决策问答)
- [7. 源码索引](#7-源码索引)

---

## 1. 总体设计

**核心边界**：知识负责「理解意图与解释结论」，DuckDB 负责「算数」。数值计算绝不交给 LLM。

```
用户提问
  └─ AnalysisController /api/chat
       └─ AnalysisOrchestrator
            ├─ plan()     : VectorService + KnowledgeService.retrieveContext → LLM prompt
            ├─ execute()  : AnalysisEngine（DuckDB SQL）
            ├─ conclude() : 再次注入知识上下文 → 结论文本
            └─ memorize() : 沉淀为 case 知识条目
                                  ↓
                      KnowledgeChangedEvent → 图谱增量同步
                                  ↓
                      KnowledgeGraphController → 前端可视化
```

系统支持 7 种知识形式，按确定性分层：确定性公式直接执行，概率性语义走向量检索。

| 形式 | kind | 存储 | 向量化 |
|---|---|---|---|
| 指标口径 | `metric` | H2 + Zvec | 是 |
| 业务术语 | `glossary` | H2 + Zvec | 是 |
| 分析模板 | `recipe` | H2 + Zvec | 是 |
| 文档块 | `document` | H2 + Zvec | 是 |
| 历史案例 | `case` | H2 + Zvec | 是 |
| 规则表达式 | `metric.formula` | H2 结构化字段 | 否（确定性执行） |
| 元数据 | — | ColumnInfo + Zvec | 混合 |

---

## 2. 分析领域模型

### 2.1 为什么用 DDD

改造前 `AnalysisPlan` 的字段都是裸字符串：`"trendd"` 拼错也能编译，`safeAgg()` 之类的字符串判断散落各处，领域知识（如「按月聚合 period 应显示 2026-01」）只能散在 SQL 拼接里——这正是 period 缺陷的根源。

改造后领域概念用枚举值对象表达，行为内聚到枚举方法上：

```
com.axiom.domain/          领域层（无框架依赖）
├── AnalysisIntent    枚举  查询/趋势/异常/对比/概览
├── Aggregation       枚举  聚合方式（含 getSql()）
├── TimeGranularity   枚举  时间粒度（含 periodSql()）
├── ChartType         枚举  图表类型
└── SortOrder         枚举  排序方向（含 getSql()）

com.axiom.model/
└── AnalysisPlan      聚合根  用上述枚举作为字段类型

com.axiom.service/
└── AnalysisEngine    领域服务  switch(intent) 穷举，调用枚举生成 SQL
```

### 2.2 枚举即行为

关键例子是 `TimeGranularity.periodSql()`，每个粒度自带正确的展示格式：

| 粒度 | format | period 示例 |
|---|---|---|
| MONTH | `%Y-%m` | `2026-01` |
| DAY | `%Y-%m-%d` | `2026-01-05` |
| WEEK | `%Y-W%V` | `2026-W02` |
| QUARTER | `%Y-Q%q` | `2026-Q1` |
| YEAR | `%Y` | `2026` |

**period 缺陷修复**：用户问「分析销售额的变化趋势」，period 列曾显示 `2026-01-01`、`2026-02-01`——`DATE_TRUNC('month', ...)` 返回的是当月第一天时间戳，与「每月总额」的语义不匹配。改用 `periodSql()` 包裹 `strftime` 后按月显示 `2026-01`，且格式规则内聚在枚举里，所有趋势分析自动受益。

### 2.3 协议兼容与类型安全

- `@JsonValue` 让枚举序列化输出小写值（`trend`、`month`），LLM 与前端无需改动
- `@JsonCreator from(String)` 同时接受小写值、大写枚举名、别名（`average`→AVG），非法值回退安全默认
- `AnalysisEngine.execute()` 用 `switch(plan.getIntent())` 穷举所有意图，新增意图时编译器提示遗漏，杜绝「静默走 default」

因此这是一次纯内部重构，对 API 与 LLM prompt 完全兼容。

五类分析复查结果：

| 分析 | 聚合来源 | 时间/维度 | 状态 |
|---|---|---|---|
| 查询 query | `getAggregation().getSql()` | dimension 分组 | 正常 |
| 趋势 trend | `getAggregation().getSql()` | `periodSql()` 格式化 | 已修 |
| 异常 anomaly | `getAggregation().getSql()` | 可选分组列 | 正常 |
| 对比 compare | `getAggregation().getSql()` | `SortOrder.getSql()` | 正常 |
| 概览 summary | 描述性统计 | — | 正常 |

次要项：异常识别的 `item` 由 `CAST(数值 AS VARCHAR)` 产生 `22100.0`（带 `.0`），不影响正确性。

---

## 3. 知识库

### 3.1 统一抽象

所有来源归一化为 `KnowledgeEntry`，共享两层存储。新增来源只需加适配器，存储和检索层不重复开发。

```
来源适配器
  ├─ 手动录入 (metric / glossary / recipe)
  ├─ 分析沉淀 (case)        ← 自动
  └─ 文档解析 (document)    ← PDF/DOCX/MD/TXT 分块
            │
       KnowledgeEntry
       ├─ KnowledgeRepository  (H2/SQLite 元数据 + 倒排索引)
       └─ KnowledgeVectorStore (Zvec knowledge_base_v2)
```

字段设计：`kind` 区分类型，`fields/tags` 存 JSON，`datasetId` 为空表示全局，`textHash`（SHA-256）做幂等去重，`formula` 存确定性规则直接执行。`knowledge_entries` 含 `version`（每次更新递增）与 `deleted`（软删除标记）列，删除只标记、查询自动过滤，可恢复。

### 3.2 混合检索

```
query → SubQuery1(embedding, HNSW)  ┐
      → SubQuery2(jieba FTS)        ┴─ MultiQuery.setRerankRrf(1) → filter → topK
filter: (datasetId='' OR datasetId=当前) AND kind=?
```

RRF 只用排名倒数 `score(d) = Σ 1/(60 + rank_r(d))`，与不同召回分支的分数尺度无关，比直接加权更鲁棒。60 是平滑常数，压缩头部排名差距。

RRF 在 Zvec native 层完成；Java 层的 Rerank 是召回后的二次精排，两者职责不同：

| 策略 | 实现 | 说明 |
|---|---|---|
| `ruleRerank` | `RuleRerankProvider` | 分词覆盖率×0.7 + 子串命中×0.3，零依赖 |
| `llmRerank` | `LlmRerankProvider` | LLM 批量给 query-doc 对打 0~1 分 |
| `auto` | `RerankService` | 有 API Key 用 LLM，否则用规则 |

流程：先召回 `candidate-size`（默认 20）条 → rerank → 取 topK。

### 3.3 双写一致性

元数据（H2）与向量（Zvec）是两次独立写入，Embedding 失败时会出现「元数据有、向量无」。方案是状态机 + outbox + 重建索引：

```
pending --向量成功--> ready
pending --向量失败--> vector_failed --reindex--> pending --> ready
```

必须**先写元数据（带 status）再写向量**：即便向量失败也有记录可扫描重建；反过来会留下无从发现的孤儿向量。`GET /api/knowledge/status` 监控，`POST /api/knowledge/reindex?force=` 修复。

### 3.4 检索降级

无 Embedding 时向量检索不可用。旧的 `keywordFallback` 用全表 `contains` 扫描，数据量大时慢。现在的方案是 `knowledge_terms` 倒排索引表：

- 入库时 `KnowledgeTokenizer` 分词：英文/数字按词，中文按 bigram（相邻两字），零外部依赖
- 降级检索：`SELECT entry_id, COUNT(*) hits WHERE term IN (...) GROUP BY entry_id ORDER BY hits DESC`
- 无命中时回退子串匹配，兼容旧数据

bigram 与 Zvec 的 jieba FTS 不是重复建设：前者是纯 SQL 降级链路，后者只在 Zvec 可用时生效，构成主链路与降级链路的关系。

### 3.5 与 Agent 的集成点

| 阶段 | 用法 |
|---|---|
| Plan 前 | 检索 metric/glossary/recipe 注入 prompt，帮助字段映射 |
| Conclude 时 | 检索业务知识，让结论有业务含义 |
| Memorize 后 | 把「问题+计划+结论」沉淀为 case（含完整结论） |

---

## 4. 知识图谱

### 4.1 定位与边界

知识图谱是**管理页的可视化旁路**，不是 Agent 推理链路的一部分：全仓只有 `KnowledgeGraphController` 调用图谱服务，`AnalysisOrchestrator` 从不查询图谱；Agent 链路只调用 `KnowledgeService.retrieveContext()` 做文本检索并注入 prompt。

图谱与混合检索是两条独立链路：

```
检索链路：query → Zvec MultiQuery（HNSW + jieba FTS）→ RRF → Rerank → 降级倒排 → prompt
图谱链路：KnowledgeEntry → 增量同步 → kg_nodes/kg_edges → BFS / 图算法 → 前端可视化
```

### 4.2 数据模型

用两张关系表在 H2/SQLite（元数据同库）中存储图结构，不引入 Neo4j：

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

| 节点类型 | 来源 | 边 | 示例 |
|---|---|---|---|
| `knowledge` | 知识条目 | — | `knowledge:<entryId>` |
| `dataset` | 条目所属数据集 | `belongs_to` | `dataset:<datasetId>` |
| `field` | 指标引用字段 | `references` | `field:销售额` |
| `tag` | 普通标签 | `tagged` | `tag:核心指标` |
| `intent` | `case` 条目的 tag | `has_intent` | `intent:trend` |

`intent` 节点来自 `kind=case` 的知识条目，其 tag 由 `AnalysisIntent.getValue()` 生成（如 `trend`、`compare`）。联合主键保证同一三元组唯一，配合「先删后插」实现幂等写入。

### 4.3 构建与同步

`KnowledgeGraphService.rebuild()` 是全量清空重建：`clear()` → `listAll()` → 逐条抽取。抽取规则：

1. 每条知识建 `knowledge:<id>` 节点
2. 有 `datasetId` → `dataset:<id>` 节点 + `belongs_to` 边
3. 遍历 `fields` → `field:<字段名>` 节点 + `references` 边
4. 遍历 `tags` → case 用 `intent:` + `has_intent`，其他用 `tag:` + `tagged`

知识条目的增删改通过 `KnowledgeChangedEvent` 领域事件自动触发增量同步，ID 列表区分 ADD/BATCH/DELETE_BATCH；REINDEX（向量重建）不触发图谱重建。失败条目进入 outbox 重试，版本水位 + 定时全量 rebuild 用于对账。

### 4.4 查询机制

**无向 BFS**：`KnowledgeGraphRepository.neighbors()` 用 `LinkedHashSet` 去重，每跳同时查出边与入边，`frontier` 为空提前终止。`references`/`belongs_to` 是有向写入，但「哪些指标共享字段」需要反向查找，所以遍历视为无向。hops 在 Service 层钳制为 1~5，防止指数级扩散。

**子图组装**：拿到 ID 集合后在 Java 层过滤 `listNodes()` / `listEdges()`，返回 `{center, hops, nodes, edges}`。

**SQLite 兼容**：H2 返回大写列名（`ID/SOURCE/TARGET`），SQLite 返回小写。图谱服务通过 `mapValue()` 做大小写不敏感读取，切换 `METADATA_TYPE=sqlite` 时子图正常返回。

### 4.5 图算法与推理

- 社区发现：标签传播，int 数组上运行，计数用可复用缓冲数组（零对象分配），迭代上限 50，收敛提前退出
- 最短路径：BFS
- 关联分析：基于邻居子图

API：`POST /api/knowledge/graph/reason/communities`、`GET /api/knowledge/graph/reason/path?from=&to=`、`GET /api/knowledge/graph/reason/related?entryId=`。

### 4.6 推理性能设计

旧实现每次推理都执行 `load()`：读取全部节点与边为 `List<Map<String,Object>>`，构造三份字符串键 Map，社区发现每次迭代对全节点排序 O(I × (V log V + E))，图变更后无失效机制。万级节点下内存与延迟不可控。

生产方案四层：

**版本化快照**：新增 `kg_meta(version)` 表，图结构每次逻辑写后 `bumpVersion()` 一次。`GraphSnapshotCache` 按版本缓存紧凑快照——版本命中复用内存快照（零数据库读取），版本变化重建并原子替换（`AtomicReference` + 双检锁），重启自然重建。

**紧凑图结构**：`GraphSnapshot` 用 int 索引替代字符串键。

| 旧结构 | 新结构 |
|---|---|
| `Map<String,String> labels` | `String[] types` |
| `Map<String,String> names` | `String[] labels` |
| `Map<String,List<String>> adj` | `int[][] adjacency` + `Map<String,Integer> index` |

内存降低约 5–10 倍，遍历无字符串哈希与装箱，`indexOf()` 提供 O(1) 查找。

**大图采样**：节点数 > `app.graph.max-nodes`（默认 10000）时，按度数降序保留 Top `sample-size`（默认 3000）节点，只保留两端都在采样集内的边。返回附带 `sampled`、`totalNodes`、`analyzedNodes`，前端显示采样提示避免误导。

**结果缓存**：社区发现按 `(version, maxIterations)` 缓存，重复请求 O(1) 返回；图结构一变版本即变，缓存自动失效。

一致性保证：

| 场景 | 行为 |
|---|---|
| 知识条目增删改 | 事件驱动增量同步 → `bumpVersion()` → 下次推理重建快照 |
| 手动 rebuild | 全量重建 → `bumpVersion()` → 缓存失效 |
| REINDEX | 不改图谱，不 bump，社区结果继续命中缓存 |
| 并发推理 | 双检锁保证单次重建；读线程拿到旧快照仍自洽 |
| 服务重启 | 缓存清空，按当前版本重建 |

配置与返回结构：

```yaml
app:
  graph:
    max-nodes: 10000   # 超过此值触发采样
    sample-size: 3000  # 采样保留节点数
```

```json
{
  "communities": [...],
  "count": 4,
  "iterations": 7,
  "sampled": false,
  "totalNodes": 37,
  "analyzedNodes": 37,
  "version": 12
}
```

内存紧张可把 `max-nodes` 降到 5000、`sample-size` 降到 1500；需要更完整社区则提高两者，但注意 O(V+E) 成本。十万级节点可考虑图算法下沉数据库（递归 CTE）或异步物化社区表，用 Louvain/Leiden 替代标签传播。

### 4.7 API 与前端

`KnowledgeGraphController`，基础路径 `/api/knowledge/graph`：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/rebuild` | 全量重建图谱 |
| GET | `` (root) | 完整图：nodes + edges + stats |
| GET | `/nodes` | 全部节点 |
| GET | `/edges` | 全部边 |
| GET | `/neighbors?nodeId=&hops=` | N 跳邻居子图（hops 钳制 1~5） |
| GET | `/stats` | 节点/边统计与按类型分组 |

前端 `KnowledgeGraphView.tsx` 用 ECharts `type:'graph', layout:'force'`（`repulsion:260, edgeLength:90`），支持拖拽缩放、边标签、选节点查 2 跳邻居，同时兼容 H2 大写键与 SQLite 小驼峰键。通过 `KnowledgePanel.tsx` 的五个 Tab 集成（条目/图谱/推理/Zvec/来源）。

### 4.8 已知限制

- `listNodes()/listEdges()` 全表加载后内存过滤，规模受限
- 前端图谱数据缺少专用 TS 类型，使用 `Record<string, unknown>` + `any`
- 图谱规模持续增大时需重新评估采样策略与算法下沉

---

## 5. 存储与数据布局

所有数据统一在 `backend/data/` 下，每种存储一个子目录：

```
backend/data/
├── h2/                       H2 元数据（含倒排索引、知识图谱表）
│   └── metadata.mv.db
├── sqlite/                   SQLite 元数据（METADATA_TYPE=sqlite 时）
│   └── metadata.db
├── duckdb/                   DuckDB 分析库（原始数据）
│   └── analysis.duckdb
├── models/bge-small-zh-v1.5/ 本地 embedding 模型
└── zvec/                     Zvec 向量库
    ├── dataset_semantics/    collection 1：字段/历史/结论
    └── knowledge_base_v2/    collection 2：知识条目（content 含 jieba FTS 索引）
```

目录由 `DataSourceConfig.ensureParentDir()` 和 `ZvecLifecycle` 自动创建。Zvec 的 collection 目录本身必须不存在才能 `createAndOpen`（首次），存在时走 `open`（重启）。`knowledge_base_v2` 相比旧 `knowledge_base` 为 `content` 字段增加了 jieba FTS 索引，首次启动检测到新 collection 为空时自动全量 reindex。

| Collection | 路径 | 用途 |
|---|---|---|
| `dataset_semantics` | `data/zvec/dataset_semantics` | 字段语义 / 历史问答 / 结论 |
| `knowledge_base_v2` | `data/zvec/knowledge_base_v2` | 知识条目（向量 + jieba FTS） |

`ZvecLifecycle` 独占 `Zvec.initialize()/shutdown()`，两个服务共享，避免 native 库重复初始化异常。

三类数据各有查看入口：

| 数据 | 存储 | 查看入口 |
|---|---|---|
| 原始数据 | DuckDB `analysis.duckdb` | 数据集 →「查看原始数据」；`GET /api/datasets/{id}/preview` |
| 分析结果 | H2 `messages.payload_json` | 对话消息图表+结论；`GET /api/sessions/{id}` |
| 知识沉淀 | H2 `knowledge_entries` + Zvec | 知识库 → 条目 →「详情」；图谱 Tab；Zvec 数据 Tab |

---

## 6. 设计决策问答

**为什么用两张关系表而不是 Neo4j？**
嵌入式部署（单 jar、H2/SQLite），指标-字段-数据集是浅层星形结构，深度通常不超过 2 跳。两张表零运维、复用现有 JDBC 与事务。代价是没有原生图算法和高效多跳遍历。万级以上节点、多跳频繁或需要路径算法时再迁 Neo4j。

**`kg_edges` 为什么用 `(source, target, relation)` 联合主键？**
同一对节点之间可能存在多种关系（既是 `references` 又是 `tagged`）。联合主键保证三元组唯一，配合「先 DELETE 后 INSERT」实现幂等，重复 rebuild 不产生重复边。

**切到 SQLite 有什么隐患？**
H2 返回大写列名，SQLite 返回小写。图谱服务曾硬编码大写键导致 N 跳子图为空，现已通过 `mapValue()` 做大小写兼容。发现方式是配置矩阵测试（H2/SQLite × 各 API）与代码评审关注 Map 键访问。

**RRF 为什么比直接加权分数更鲁棒？公式里 60 是什么？**
向量相似度和 FTS 分数尺度不同，直接加权需要归一化且容易被某一路主导。RRF 只用排名倒数 `1/(60+rank)`，与分数尺度无关。60 是平滑常数，压缩头部排名差距，避免第 1 名权重过大。

**双写一致性为什么不能先写向量再写元数据？**
先写元数据（带 status）：向量失败仍有记录可扫描重建；反过来元数据失败会留下孤儿向量且无从发现。

**「无 API Key 也能完整运行」的准确含义？**
默认 embedding 是本地 ONNX，不需要 Key；无 `DEEPSEEK_API_KEY` 时 Planner 降级规则引擎、结论用模板、rerank 走 `ruleRerank`。只有显式配 `embedding.provider=api` 且无 Key 时向量写入失败，检索自动走倒排索引。

**GraphRAG 增强可以如何设计？**
检索阶段取 top 实体的 1~2 跳子图，把「指标 GMV → 字段 销售额/成本 → 关联指标 客单价」序列化为文本注入 Plan prompt；Conclude 时用图路径解释结论；控制 token 量（限定节点数/跳数），并保证图谱与知识条目实时一致，避免注入错误口径。

---

## 7. 源码索引

| 文件 | 职责 |
|---|---|
| `backend/.../controller/AnalysisController.java` | REST + SSE 入口 |
| `backend/.../service/AnalysisOrchestrator.java` | Plan/Execute/Conclude/Memorize 编排 |
| `backend/.../service/AnalysisEngine.java` | 领域服务，switch 穷举生成 SQL |
| `backend/.../service/KnowledgeService.java` | 知识门面、retrieveContext、mapValue |
| `backend/.../service/KnowledgeVectorStore.java` | 混合检索、RRF、filter |
| `backend/.../service/KnowledgeGraphService.java` | 图谱抽取与子图组装 |
| `backend/.../repository/KnowledgeGraphRepository.java` | 表结构与 BFS |
| `backend/.../service/VectorService.java` | 向量写入与降级 |
| `backend/.../graph/GraphSnapshotCache.java` | 版本快照缓存 |
| `backend/.../service/KnowledgeGraphReasoner.java` | 社区发现/路径/关联分析 |
| `backend/.../llm/LlmProviderFactory.java` | 策略发现与工厂 |
| `frontend/src/components/KnowledgePanel.tsx` | 知识库五视图容器 |
| `frontend/src/components/KnowledgeGraphView.tsx` | ECharts 力导向图 |
| `frontend/src/components/AnalysisChart.tsx` | ECharts 分析结果图表 |
