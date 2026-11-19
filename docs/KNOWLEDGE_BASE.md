# 知识库增强设计文档 / Knowledge Base Design

> 对应实现版本 / Implementation version: 2026-11-18
> 本文覆盖：存储形式、统一抽象、混合检索、双写一致性、降级索引、Rerank、知识图谱、数据查看、配置与 API。
>
> 相关文档 / Related docs:
> - 分析领域 DDD 模型与枚举值对象 / DDD analysis domain model: [DDD_ANALYSIS_MODEL.md](DDD_ANALYSIS_MODEL.md)
> - 本地 Embedding 部署（零外部依赖）/ Local embedding deployment: [LOCAL_DEPLOYMENT.md](LOCAL_DEPLOYMENT.md)

## 1. 设计目标 / Design Goals

让数据分析 Agent 从「只理解数据结构」升级为「理解业务语义」：
Upgrade the agent from "understanding data structure" to "understanding business semantics":

- **指标口径**：GMV 怎么算？对应哪些字段？/ How is GMV calculated? Which fields?
- **业务术语**：华东大区是什么？业务黑话映射到字段 / What is "East China region"?
- **历史复用**：相似问题复用过往分析思路 / Reuse past analysis approaches.
- **结论增强**：分析结论引用业务背景 / Conclusions reference business context.

**核心边界**：知识负责「理解意图与解释结论」，DuckDB 负责「算数」。数值计算绝不交给 LLM。
**Core boundary**: Knowledge handles intent/conclusion; DuckDB handles computation. Numeric computation is never delegated to the LLM.

## 2. 存储形式 / Knowledge Storage Forms

系统支持 7 种知识形式，按确定性分层：
7 knowledge forms, layered by determinism:

| 形式 / Form | kind | 存储 / Storage | 向量化 / Embedding |
|---|---|---|---|
| 指标口径 / Metric | `metric` | H2 + Zvec | 是 / Yes |
| 业务术语 / Glossary | `glossary` | H2 + Zvec | 是 / Yes |
| 分析模板 / Recipe | `recipe` | H2 + Zvec | 是 / Yes |
| 文档块 / Document | `document` | H2 + Zvec | 是（未来模块）/ future |
| 历史案例 / Case | `case` | H2 + Zvec | 是 / Yes |
| 规则表达式 / Rule | `metric.formula` | H2 结构化字段 | 否（确定性执行）/ No |
| 元数据 / Metadata | — | ColumnInfo + Zvec | 混合 / Hybrid |

**确定性知识（公式）直接执行，概率性知识（语义）走向量检索。**

## 3. 统一抽象 / Unified Abstraction

所有来源归一化为 `KnowledgeEntry`，共享两层存储：
All sources normalize to `KnowledgeEntry`, sharing two storage layers:

```
来源适配器 / Adapters
  ├─ 手动录入 / Manual  (metric / glossary / recipe)
  ├─ 分析沉淀 / Analysis (case)   ← 自动
  └─ 文档解析 / Document (document) ← 未来
            │
       KnowledgeEntry
       ├─ KnowledgeRepository  (H2/SQLite 元数据 + 倒排索引)
       └─ KnowledgeVectorStore (Zvec knowledge_base)
```

新增来源只需加适配器，存储和检索层不重复开发。

## 4. 数据目录结构 / Data Directory Layout

所有数据统一在 `backend/data/` 下，**每种存储一个子目录**（不再混杂在根目录）：
All data lives under `backend/data/`, one subdirectory per store:

```
backend/data/
├── h2/                       H2 元数据（含倒排索引、知识图谱表）
│   └── metadata.mv.db
├── sqlite/                   SQLite 元数据（METADATA_TYPE=sqlite 时）
│   └── metadata.db
├── duckdb/                   DuckDB 分析库（原始数据）
│   └── analysis.duckdb
└── zvec/                     Zvec 向量库
    ├── dataset_semantics/    collection 1：字段/历史/结论
    └── knowledge_base/       collection 2：知识条目
```

- 目录由 `DataSourceConfig.ensureParentDir()` 和 `ZvecLifecycle` 自动创建
- Zvec 的 collection 目录**本身**必须不存在才能 `createAndOpen`（首次）；存在时走 `open`（重启）
- 迁移旧数据：把 `data/metadata.mv.db` → `data/h2/`，`data/analysis.duckdb` → `data/duckdb/`，`data/vectors` → `data/zvec/dataset_semantics`，`data/knowledge_vectors` → `data/zvec/knowledge_base`

## 5. 双 Collection 与生命周期 / Dual Collections & Lifecycle

| Collection | 路径 | 用途 |
|---|---|---|
| `dataset_semantics` | `data/zvec/dataset_semantics` | 字段语义 / 历史问答 / 结论 |
| `knowledge_base` | `data/zvec/knowledge_base` | 知识条目 |

`ZvecLifecycle` 独占 `Zvec.initialize()/shutdown()`，两个服务共享，避免 native 库重复初始化异常。

## 6. 混合检索 / Hybrid Retrieval

`KnowledgeVectorStore.hybridSearch()` 使用 Zvec 原生 MultiQuery：
```
query → SubQuery1(embedding, HNSW)  ┐
      → SubQuery2(jieba FTS)        ┴─ MultiQuery.setRerankRrf(1) → filter → topK
filter: (datasetId='' OR datasetId=当前) AND kind=?
```
RRF 只用排名倒数，不依赖不同召回分支的分数尺度，更鲁棒：
\[
\text{score}(d) = \sum_{r} \frac{1}{60 + \text{rank}_r(d)}
\]

## 7. 双写一致性 / Dual-Write Consistency

**问题**：元数据（H2）与向量（Zvec）是两次独立写入，Embedding 失败时会出现「元数据有、向量无」的不一致。

**方案**：状态机 + outbox + 重建索引
```
pending --向量成功--> ready
pending --向量失败--> vector_failed --reindex--> pending --> ready
```
- `persistWithVector()`：先写 H2（pending）→ 写向量 → 按结果更新 `ready`/`vector_failed`
- `knowledge_entries.status` 持久化状态；`GET /api/knowledge/status` 监控
- `POST /api/knowledge/reindex?force=false`：扫描 pending/failed 重建；`force=true` 重建全部
- 知识详情页显式展示向量状态，不隐瞒不一致

## 8. 检索降级与倒排索引 / Fallback & Inverted Index

**问题**：无 Embedding Key 时向量检索不可用；旧的 `keywordFallback` 用全表 `contains` 扫描，数据量大时慢。

**方案**：`knowledge_terms` 倒排索引表
- 入库时 `KnowledgeTokenizer` 分词：英文/数字按词，中文按 bigram（相邻两字），零外部依赖
- `knowledge_terms(term, entry_id)` + term 索引
- 降级检索：`SELECT entry_id, COUNT(*) hits WHERE term IN (...) GROUP BY entry_id ORDER BY hits DESC`
- 命中数即相关性分数；兼容旧数据：无命中时回退子串匹配

## 9. Rerank 重排序 / Reranking

召回后对候选精排，策略模式 `RerankProvider`：
Recall then refine candidates with a strategy pattern:

| 策略 / Strategy | 实现 | 说明 |
|---|---|---|
| `ruleRerank` | `RuleRerankProvider` | 分词覆盖率×0.7 + 子串命中×0.3，零依赖 |
| `llmRerank` | `LlmRerankProvider` | LLM 批量给 query-doc 对打 0~1 分 |
| `auto` | `RerankService` | 有 DeepSeek Key 用 LLM，否则用规则 |

流程：先召回 `candidate-size`（默认20）条 → rerank → 取 topK。

## 10. 知识图谱 / Knowledge Graph

从知识条目抽取实体关系，存储于 H2 两张表 `kg_nodes` / `kg_edges`：
Extracts entities & relations from knowledge into two H2 tables:

| 节点类型 / Node | 来源 / Source | 边 / Edge |
|---|---|---|
| `knowledge` | 知识条目 | — |
| `dataset` | 条目所属数据集 | `belongs_to` |
| `field` | 指标关联字段 | `references` |
| `tag` / `intent` | 条目标签（case 的标签为 intent）| `tagged` / `has_intent` |

- `POST /api/knowledge/graph/rebuild` 从现有知识重建
- `GET /api/knowledge/graph` 返回完整图（节点+边+统计）
- `GET /api/knowledge/graph/neighbors?nodeId=&hops=` BFS 查询 N 跳邻居
- 前端用 ECharts graph 力导向图可视化，支持聚焦节点

## 11. 与 Agent 的集成点 / Integration Points

| 阶段 / Stage | 用法 / Usage |
|---|---|
| Plan 前 | 检索 metric/glossary/recipe 注入 prompt，帮助字段映射 |
| Conclude 时 | 检索业务知识，让结论有业务含义 |
| Memorize 后 | 把「问题+计划+结论」沉淀为 case（含完整结论，修复过清空 bug）|

## 12. API 总览 / API Overview

### 知识库 / Knowledge
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/knowledge/entry` | 录入知识 |
| POST | `/api/knowledge/document` | 上传文档（PDF/DOCX/MD/TXT）自动分块入库 |
| GET | `/api/knowledge` | 列表 + 类型统计 |
| GET | `/api/knowledge/kind/{kind}` | 按类型列出 |
| GET | `/api/knowledge/{id}/detail` | 详情：元数据+向量状态+溯源+处理链路 |
| GET | `/api/knowledge/status` | 双写状态统计 |
| POST | `/api/knowledge/reindex?force=` | 重建向量索引 |
| POST | `/api/knowledge/search` | 混合检索测试（含 rerank）|
| DELETE | `/api/knowledge/{id}` | 软删除（可恢复）|
| POST | `/api/knowledge/{id}/restore` | 恢复软删除的条目 |

### 图谱自动同步 / Graph Auto-sync
知识条目增删改通过 `KnowledgeChangedEvent` 领域事件自动触发图谱重建，无需手动 rebuild。
/ Entry changes publish `KnowledgeChangedEvent`; the graph auto-rebuilds via `@EventListener`.

### 版本与软删除 / Versioning & Soft Delete
`knowledge_entries` 含 `version`（每次更新递增）与 `deleted`（软删除标记）列。删除只标记、查询自动过滤，可恢复。

### 知识图谱 / Graph
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/knowledge/graph/rebuild` | 重建图谱 |
| GET | `/api/knowledge/graph` | 完整图数据 |
| GET | `/api/knowledge/graph/neighbors?nodeId=&hops=` | N 跳邻居 |

### Zvec 查看 / Inspection
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/zvec/collections` | 两个 collection 概览（文档数、索引数）|
| GET | `/api/zvec/collections/{name}/docs?limit=` | 列出文档 |
| GET | `/api/zvec/collections/{name}/docs/{id}` | 单文档详情（向量显示维度）|

## 13. 数据在哪看 / Where to View Data

三类数据各有入口：
Three kinds of data, each with an entry:

| 数据 | 存储 | 查看入口 |
|---|---|---|
| 原始数据 | DuckDB `data/duckdb/analysis.duckdb` | 左侧数据集→「查看原始数据」；`GET /api/datasets/{id}/preview` |
| 分析结果 | H2 `messages.payload_json` | 对话消息图表+结论+「查看原始数据」；`GET /api/sessions/{id}` |
| 知识沉淀 | H2 `knowledge_entries` + Zvec | 知识库→条目→「详情」；图谱 Tab；Zvec 数据 Tab |

```
上传 → DuckDB（原始数据）
     → 提问 → SQL 分析 → 对话图表+结论（分析结果）
     → 沉淀 → 知识库 case（详情看处理链路）
     → 抽取 → 知识图谱（实体关系）
```

## 14. 管理页视图 / Management UI Views

知识库页顶部有三个 Tab：
1. **知识条目**：检索测试、录入、列表、详情（含双写状态与处理链路）
2. **知识图谱**：力导向图，重建、聚焦节点查邻居
3. **Zvec 数据**：切换 collection、看文档列表、单文档详情

## 15. 配置 / Configuration

```yaml
app:
  storage:
    duckdb-path: ./data/duckdb/analysis.duckdb
    h2-url: jdbc:h2:file:./data/h2/metadata;AUTO_SERVER=TRUE
  zvec:
    enabled: true
    dimension: 1024
    path: ./data/zvec/dataset_semantics
    knowledge-path: ./data/zvec/knowledge_base
  llm:
    deepseek: { api-key: ${DEEPSEEK_API_KEY:} }
    embedding: { base-url: https://api.siliconflow.cn/v1, api-key: ${EMBEDDING_API_KEY:}, model: BAAI/bge-m3 }
  rerank:
    provider: auto      # auto | llm | rule
    enabled: true
    candidate-size: 20
```

未配置 Embedding Key 时：向量写入失败（状态 `vector_failed`），检索自动走倒排索引降级，rerank 用规则策略——系统完全可用。

## 16. 未实现 / Roadmap

- 文档解析适配器（PDF/Word/MD 分块入库）
- 权限隔离 / 多租户
- 知识版本管理与软删除
- 图算法（社区发现、最短路径）与更丰富的图谱推理
