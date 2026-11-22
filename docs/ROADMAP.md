# 产品路线图 / Product Roadmap

> 整理日期 / Updated: 2026-11-21
> 产品定位：本地优先、隐私可控的「数据分析 + 知识沉淀」Agent
> Local-first, privacy-conscious Agent for data analysis and knowledge accumulation.

## 一、已完成 / Completed

### 核心功能 / Core features

| 模块 | 能力 |
|---|---|
| 数据接入 | CSV/JSON/Excel 上传、粘贴、内置示例、JDBC 数据源直连（MySQL/PostgreSQL，前端已接入）|
| 分析引擎 | 查询/趋势/异常/对比/概览 5 类分析，DDD 枚举值对象 |
| LLM | DeepSeek 策略模式、运行时热配置、无 Key 规则引擎降级 |
| Embedding | 本地 bge-small-zh-v1.5 ONNX（默认，零外部依赖）/ OpenAI 兼容 API（可选） |
| 存储 | DuckDB（分析）+ H2/SQLite（元数据）+ Zvec（双 collection 向量）|
| 知识库 | 统一 KnowledgeEntry、混合检索 + RRF、Rerank、双写一致性、倒排索引、文档解析入库、来源批次管理（前端已接入）|
| 知识图谱 | 实体关系抽取、增量自动同步、BFS 邻居查询、社区发现、最短路径、关联分析（前端已接入）、ECharts 可视化 |
| 前端 | 数据集管理、SSE 对话、ECharts 图表、知识库五视图（条目/图谱/推理/Zvec/来源）、文档上传、向量状态与重建、模型选择、会话重命名 |
| 部署 | 单 jar 常驻、Docker 一键部署、公网 HTTPS（Nginx overlay）、备份恢复脚本 |
| 测试 | 后端 10 个单元测试（分析引擎 SQL、知识库双写、SSE 校验）+ 前端 7 个 SSE 解析测试 |
| 文档 | README / 知识库 / 知识图谱 / 本地与 Docker 部署 / 项目问答 / DDD 模型 / 路线图 |

### 已完成批次 / Completed batches

| # | 事项 | 类型 | 实现 |
|---|---|---|---|
| 1 | 图谱自动同步（增量） | Bug 修复 | `KnowledgeChangedEvent` + 按 entryId 增量 upsert，BATCH/REINDEX 全量兜底 |
| 2 | SQLite 邻居查询大小写兼容 | Bug 修复 | `mapValue()` 大小写不敏感读取 H2/SQLite 列名 |
| 3 | 文档解析适配器 | P1 | PDFBox + POI，PDF/DOCX/MD/TXT 语义分块入库 |
| 4 | JDBC 数据源直连 | P1 | MySQL/PostgreSQL 只读导入，密码 AES 加密，前端管理面板 |
| 5 | 启动时自动重试 pending 向量 | P1 | `ApplicationReadyEvent` 异步重试 |
| 6 | 检索 token 预算与去重 | P1 | `contextCharBudget` + LinkedHashSet 去重 |
| 7 | 知识版本管理与软删除 | P2 | `version`/`deleted` 列 + restore API |
| 8 | sourceId 批次管理 | P2 | `knowledge_sources` 表，前端来源视图与级联删除 |
| 9 | 图谱推理前端 | P2 | 社区发现/最短路径/关联分析 UI |
| 10 | 备份恢复脚本 | P2 | `scripts/backup.sh create/list/restore/prune` |
| 11 | 前端代码分割 | P2 | `manualChunks` + ECharts 组件懒加载 |
| 14 | 知识图谱推理 | P2 | 社区发现（标签传播）/ 最短路径（BFS）/ 关联分析 |
| 15 | 运行时 LLM 配置 | P2 | `GET/POST /api/config/llm` 热更新 + 前端配置面板 |
| 16 | 前后端测试覆盖 | P2 | 后端 JUnit5 + 前端 node:test |

## 二、待实现 / Backlog（产品化方向，待确认）

| # | 事项 | 说明 | 前置条件 |
|---|---|---|---|
| 17 | 多用户与权限隔离 | 用户体系、知识共享/隔离 | 确定团队化方向 |
| 18 | 公网 SaaS 化 | 当前 Docker + HTTPS 已支持单机公网访问；多租户 SaaS 需账号体系 | 确定公网方向 |
| 19 | 监控 / 日志 / 告警 | 运行状态可观测（指标、告警规则） | 部署规模上来后 |

## 三、方向决策依赖 / Decisions Needed

- **多用户/权限（17）**：取决于「单人工具 vs 团队产品」
- **公网 SaaS（18）**：当前 Docker + HTTPS 已满足单机公网访问，是否做多租户取决于产品定位
- **监控告警（19）**：单机部署可用日志 + 健康检查；规模化后再引入指标系统

## 四、变更记录 / Changelog

- 2026-11-21：完成图谱增量同步、全部前端入口接入、运行时 LLM 配置、备份脚本、前后端测试
- 2026-11-20：整合重复 P3 段落，修正已完成项状态，补充 Docker 部署与测试进展
- 2026-11-19：图谱自动同步、SQLite 兼容、文档入库、知识健壮性批次交付
