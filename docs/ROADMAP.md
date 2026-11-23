# 产品路线图 / Product Roadmap

> 更新：2026-11-23
> 产品定位：本地优先、隐私可控的「数据分析 + 知识沉淀」Agent。

## 一、已完成 / Completed

| 模块 | 能力 |
|---|---|
| 数据接入 | CSV/JSON/Excel 上传、粘贴、内置示例、JDBC 数据源直连（MySQL/PostgreSQL） |
| 分析引擎 | 查询/趋势/异常/对比/概览 5 类分析，DDD 枚举值对象 |
| LLM | DeepSeek/通义千问双厂商（OpenAI 兼容策略模式，可插拔）、运行时热配置与切换、无 Key 降级规则引擎 |
| Embedding | 本地 bge-small-zh-v1.5 ONNX（默认，零外部依赖）/ OpenAI 兼容 API（可选） |
| 存储 | DuckDB（分析）+ H2/SQLite（元数据）+ Zvec（双 collection 向量） |
| 知识库 | 统一 KnowledgeEntry、混合检索 + RRF、Rerank、双写一致性、倒排索引、文档解析入库、来源批次管理 |
| 知识图谱 | 实体关系抽取、增量自动同步、BFS 邻居查询、社区发现、最短路径、关联分析、ECharts 可视化 |
| 前端 | 数据集管理、SSE 对话、ECharts 图表、知识库五视图（条目/图谱/推理/Zvec/来源）、文档上传、向量状态与重建、模型选择、会话重命名 |
| 部署 | 单 jar 常驻、Docker 一键部署、公网 HTTPS（Nginx overlay）、备份恢复脚本 |
| 测试 | 后端 64 个单元测试（行覆盖 34.5%）+ 前端 13 个测试（`sse.ts` 100% 覆盖）；`scripts/coverage.sh` 一键生成报告 |
| 文档 | README / 架构设计 / 部署与扩展 / LLM 扩展指南 / 路线图 |

### 已完成批次

| # | 事项 | 类型 | 实现 |
|---|---|---|---|
| 1 | 图谱自动同步（全增量） | Bug 修复 | `KnowledgeChangedEvent` 携带 ID 列表，ADD/BATCH/DELETE_BATCH 全增量，REINDEX 不触发图谱重建 |
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
| 12 | 知识图谱推理 | P2 | 社区发现（标签传播）/ 最短路径（BFS）/ 关联分析 |
| 13 | 运行时 LLM 配置 | P2 | `GET/POST /api/config/llm` 热更新 + 前端配置面板 |
| 14 | 前后端测试覆盖 | P2 | 后端 JUnit5 + 前端 node:test |
| 15 | 通义千问 Provider | P2 | OpenAI 兼容基类抽取，DeepSeek/通义共享协议层 |
| 16 | 图谱推理性能 | P2 | 版本快照、紧凑图、大图采样、结果缓存 |
| 17 | 覆盖率报告固定输出 | P2 | `scripts/coverage.sh` + `reports/coverage/`，不受 `mvn clean` 影响 |

## 二、明确不做 / Out of Scope

| 事项 | 决策 |
|---|---|
| 多用户与权限隔离 | 不做团队化，定位单人工具 |
| 公网 SaaS 化 | 不做 SaaS；Docker + HTTPS 单机公网部署已足够 |
| 监控 / 日志 / 告警 | 不引入监控系统；用日志 + `/api/health` 健康检查即可 |

## 三、变更记录 / Changelog

- 2026-11-23：文档重构为 4 篇（README / 架构设计 / 部署与扩展 / 路线图）；覆盖率报告固定输出到 `reports/coverage/`；项目更名为 Axiom，包名统一为 `com.axiom`
- 2026-11-22：新增通义千问 Provider，重构 LLM 策略模式与配置结构，图谱同步全量去重建；测试 24→64、前端 7→13
- 2026-11-21：完成图谱增量同步、全部前端入口接入、运行时 LLM 配置、备份脚本、前后端测试
- 2026-11-20：整合重复段落，修正已完成项状态，补充 Docker 部署与测试进展
- 2026-11-19：图谱自动同步、SQLite 兼容、文档入库、知识健壮性批次交付
