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
| 测试 | 后端 96 个单元测试 + 前端 15 个测试（`sse.ts` 100% 覆盖）；`scripts/coverage.sh` 一键生成报告 |
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
| 18 | 多厂商兼容与健壮性加固 | Bug 修复 | ChatOptions jsonMode 参数错位（plan 被强加 JSON 模式的多厂商兼容根因）、公式指标异常检测路径、vite 配置产物遮蔽、SSE 线程池优雅停机 4 个高危；FormulaCompiler 守卫加固（意外 token 报语法错/括号深度上限/深度只统计引用链）；Zvec 检视串行锁、Excel 日期解析、空 apiKey 保护、图谱重建事务；向量热路径批量化 + 问题向量 LRU 缓存；前端失败态/IME 守卫/流中断状态一致；nginx gzip 与缓存、备份恢复、模型路径修正，共 30+ 项 |
| 19 | 分析列选择与 Excel 流式解析 | P2 | compare/query 指标校验（非数值列且非公式字段提前报错）；compare 维度兜底跳过 ID 类列（纯命名/下划线后缀/中英混排「订单ID」）；Excel .xlsx 改 SAX 事件流式（内存只随表头+当前行增长），日期 ISO 输出与 DOM 路径对齐，200k 行超限报错不静默截断 |
| 20 | 第三轮健壮性修复（3 高危 + 6 中危 + 低危） | Bug 修复 | 公式指标被 validatePlan 误当幻觉列剔除（主链路必降级规则引擎）；FormulaCompiler 一元符号链无深度上限可栈溢出（与括号共享 depth 64）；前端流竞态（startNewChat 未复位 isLoading/abortRef、openSession 旧流抢占清理权，改 chatTokenRef 递归守卫）。中危：向量 upsert 逐条隔离 embed 失败、知识库 reindex 越界守卫 + 删改操作纳入 reindexLock + 空内容进 skipped、rerank 失败保留召回原分数（不再滤空知识注入）、外接数据源元数据落库失败补偿 dropTable、SSE error 事件双前缀 + temperature=0 合法值、LLM 配置 JSON null 防护。低危：JDBC 登录超时与字符串字面量分号检测、Zvec 404 语义区分、倒排索引 batchUpdate、SessionOptions 资源释放、图谱/推理视图卸载守卫、nginx 安全头补齐、前端 tsconfig 收敛 |

## 二、明确不做 / Out of Scope

| 事项 | 决策 |
|---|---|
| 多用户与权限隔离 | 不做团队化，定位单人工具 |
| 公网 SaaS 化 | 不做 SaaS；Docker + HTTPS 单机公网部署已足够 |
| 监控 / 日志 / 告警 | 不引入监控系统；用日志 + `/api/health` 健康检查即可 |

## 三、变更记录 / Changelog

- 2026-11-25：第三轮全量审查修复（详见已完成批次 #20）：公式指标主链路打通（validatePlan 不再误杀公式指标）、一元符号链栈溢出防护、前端流竞态守卫；6 项中危（向量逐条隔离、知识库并发与越界、rerank 降级语义、数据源孤儿表补偿、SSE/temperature、JSON null 防护）与低危择要；后端测试 95→96
- 2026-11-24：compare/query 指标校验与 compare 维度兜底跳过 ID 类列；Excel .xlsx 改 SAX 流式解析（大文件内存可控、日期/布尔与 DOM 路径行为对齐、200k 行护栏）；后端测试 71→95；端到端对账验证分析数值与原始数据逐项一致
- 2026-11-24：全量审查修复 30+ 项（详见已完成批次 #18）：4 个高危（plan 误加 JSON 模式 / 公式指标异常路径 / vite 产物遮蔽 / SSE 线程池停机）、FormulaCompiler 守卫加固、向量热路径批量化、前端健壮性、部署配置修正
- 2026-11-24：指标公式确定性执行落地（FormulaCompiler 递归下降解析 + aggregate-then-compute 编译 + NULLIF 除零防护 + 公式引用公式/循环检测 + 录入语法校验）；领域枚举标签开放 `GET /api/labels`（标签单一事实来源）；清理历史死代码
- 2026-11-23：文档重构为 5 篇（README / 架构设计 / 部署与扩展 / LLM 扩展指南 / 路线图）；覆盖率报告固定输出到 `reports/coverage/`；项目更名为 Axiom，包名统一为 `com.axiom`
- 2026-11-22：新增通义千问 Provider，重构 LLM 策略模式与配置结构，图谱同步全量去重建；测试 24→71、前端 7→15
- 2026-11-21：完成图谱增量同步、全部前端入口接入、运行时 LLM 配置、备份脚本、前后端测试
- 2026-11-20：整合重复段落，修正已完成项状态，补充 Docker 部署与测试进展
- 2026-11-19：图谱自动同步、SQLite 兼容、文档入库、知识健壮性批次交付
