# 图谱推理性能设计 / Graph Reasoning Performance Design

> 生产级实现说明：版本化快照 + 紧凑图结构 + 大图采样 + 结果缓存。
> Production design: versioned snapshots + compact graph + large-graph sampling + result caching.

## 1. 问题 / Problem

旧实现每次推理调用（社区发现/最短路径/关联分析）都执行 `load()`：

- 从 H2/SQLite 读取**全部**节点与边为 `List<Map<String,Object>>`
- 构造三份 `Map<String,…>`（labels/names/adjacency），字符串键 + 装箱开销大
- 社区发现每次迭代对全节点排序，O(I × (V log V + E))
- 图变更后无失效机制，结果可能陈旧；重复请求重复计算

对万级节点图，内存与延迟都不可控。

## 2. 生产方案 / Production Design

### 2.1 版本化快照（核心）/ Versioned snapshot

- 新增 `kg_meta(version)` 表，记录图结构版本号
- `KnowledgeGraphService` 在完成一次逻辑写（rebuild 或增量同步）后调用 `bumpVersion()` **一次**
- `GraphSnapshotCache` 按版本缓存紧凑快照：
  - `version` 命中 → 复用内存快照，**零数据库读取**
  - `version` 变化 → 重建并原子替换（`AtomicReference` + 双检锁，并发只重建一次）
  - 服务重启 → 缓存为空自然重建

### 2.2 紧凑图结构 / Compact graph

`GraphSnapshot` 用 int 索引替代字符串键：

| 旧结构 | 新结构 |
|---|---|
| `Map<String,String> labels` | `String[] types` |
| `Map<String,String> names` | `String[] labels` |
| `Map<String,List<String>> adj` | `int[][] adjacency` + `Map<String,Integer> index` |

内存降低约 5–10 倍，遍历无字符串哈希与装箱。`indexOf()` 提供 O(1) ID 查找。

### 2.3 大图采样 / Large-graph sampling

当节点数 > `app.graph.max-nodes`（默认 10000）：

1. 用边计算各节点度数
2. 按度数降序保留 Top `sample-size`（默认 3000）节点
3. 只保留两端都在采样集内的边
4. 返回结果附带 `sampled=true`、`totalNodes`、`analyzedNodes`

前端社区面板会显示「大图采样 / Sampled: x/y 节点」提示，避免误导。

### 2.4 结果缓存 / Result caching

社区发现结果按 `(version, maxIterations)` 缓存，重复请求 O(1) 返回。
图结构一变版本即变，缓存自动失效。

### 2.5 算法优化 / Algorithm tuning

- 标签传播在 int 数组上运行，计数用可复用缓冲数组（零对象分配）
- 快照 IDs 由 Repository `ORDER BY type, id` 保证处理顺序稳定，结果可复现
- 迭代上限 50，收敛提前退出
- 度数 Top-K 采样用 `HashMap` 计数 + 一次排序，O(E + V log V)

## 3. 配置 / Configuration

```yaml
app:
  graph:
    max-nodes: 10000   # 超过此值触发采样 / Sampling threshold
    sample-size: 3000  # 采样保留节点数 / Nodes kept when sampling
```

环境变量 / Env vars：`GRAPH_MAX_NODES`、`GRAPH_SAMPLE_SIZE`。

调优建议 / Tuning：
- 内存紧张：降低 `max-nodes`（如 5000）与 `sample-size`（如 1500）
- 需要更完整社区：提高两者，但注意 O(V+E) 内存与迭代成本

## 4. API 返回结构 / API response

`GET /api/knowledge/graph/reason/communities` 新增字段（原有字段保持不变）：

```json
{
  "communities": [...],
  "count": 4,
  "iterations": 7,
  "sampled": false,        // 是否采样 / Whether sampled
  "totalNodes": 37,        // 图总节点数 / Total nodes in graph
  "analyzedNodes": 37,     // 实际参与计算节点数 / Nodes actually analyzed
  "version": 12            // 图版本号 / Graph version
}
```

`shortestPath` / `related` 返回结构不变，但内部同样走缓存快照。

## 5. 一致性保证 / Consistency

| 场景 | 行为 |
|---|---|
| 知识条目增删改 | 事件驱动增量同步图谱 → `bumpVersion()` → 下次推理重建快照 |
| 手动 rebuild | 全量重建 → `bumpVersion()` → 缓存失效 |
| REINDEX（向量重建） | 不改图谱，不 bump，社区结果继续命中缓存 |
| 并发推理 | 双检锁保证单次重建；读线程拿到旧快照仍自洽（版本一致） |
| 服务重启 | 缓存清空，按当前版本重建 |

## 6. 测试 / Tests

- `GraphSnapshotCacheTest`：版本命中复用、版本变更重建、大图度数采样、空图
- `KnowledgeGraphReasonerTest`：int 索引结构上的社区/路径/关联正确性 + 采样元数据

## 7. 进一步演进 / Future work

若图谱规模持续增大（十万级+），可考虑：
- 图算法下沉到数据库（递归 CTE 做 BFS）或引入嵌入式图库
- 社区结果异步物化到 `kg_communities` 表，查询纯 SQL
- Louvain/Leiden 替代标签传播以获得更稳定的社区质量

当前方案在**单机嵌入式**约束下，把万级节点的内存与延迟控制在可预测范围内，无需新增外部依赖。
