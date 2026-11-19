# 数据分析 Agent

一个全栈 AI 数据分析应用：上传数据，用自然语言提问，自动完成**数据查询、趋势分析、异常识别、指标对比**，并生成**分析结论与可视化图表**。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | React 18 + TypeScript + Vite + TDesign React + ECharts |
| 后端 | Java 17 + Spring Boot 3.3 |
| 分析库 | DuckDB（嵌入式 OLAP，列式聚合） |
| 元数据库 | H2（默认）/ SQLite（配置可切换） |
| 向量库 | Zvec（嵌入式，双 collection：数据集语义 / 知识库） |
| 大模型 | DeepSeek（策略模式封装，可插拔）+ OpenAI 兼容 embedding |
| 知识库 | 统一 KnowledgeEntry + 混合检索（向量 + FTS + RRF），详见 [docs/KNOWLEDGE_BASE.md](docs/KNOWLEDGE_BASE.md) |
| 部署 | 单个可执行 jar（前端静态资源内嵌），常驻服务 |

## 架构

```
用户提问
   │
   ▼
[前端 React] ──HTTP/SSE──> [Spring Boot]
                              │
              ┌───────────────┼────────────────┐
              ▼               ▼                ▼
        LlmProvider      AnalysisEngine    VectorService
        (DeepSeek策略)   (DuckDB SQL)      (Zvec)
              │               │                │
              └──── 分析计划 JSON ────┘                │
                          │                            │
                   结论生成 ◄──────── 语义检索 ────────┘
                          │
                    H2/SQLite 元数据（会话/消息/数据集）
```

**设计要点**：大模型只负责「自然语言 → 结构化分析计划」和「结果 → 结论」的语义转换；所有数值计算由确定性引擎（DuckDB SQL）执行，保证结果可复现、不编造数字。当未配置 API Key 时，自动降级到内置规则引擎，核心分析功能依然可用。

## 快速开始

### 1. 配置（可选）

模型配置在 `backend/src/main/resources/application.yml`，也可通过环境变量覆盖：

```bash
export DEEPSEEK_API_KEY=sk-你的密钥
export EMBEDDING_API_KEY=你的嵌入服务密钥   # 可选，用于向量检索
```

不配置密钥也能运行，使用规则引擎降级模式。

### 2. 构建

```bash
# 前端
cd frontend && npm install && npm run build

# 将前端产物复制到后端静态目录
cd .. && rm -rf backend/src/main/resources/static/assets
cp -r frontend/dist/* backend/src/main/resources/static/

# 后端 jar
cd backend && mvn clean package -DskipTests
```

### 3. 运行

```bash
cd backend

# 方式一：部署脚本（推荐，常驻后台）
./deploy.sh start        # 启动
./deploy.sh status       # 查看状态
./deploy.sh restart      # 重启
./deploy.sh stop         # 停止

# 方式二：前台运行
java -jar target/data-analysis-agent-1.0.0.jar
```

访问 **http://localhost:8080**

## API 概览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| GET | `/api/datasets` | 数据集列表 |
| POST | `/api/datasets/upload` | 上传 CSV/JSON/Excel |
| POST | `/api/datasets/paste` | 粘贴文本导入 |
| POST | `/api/datasets/sample` | 创建示例数据集 |
| GET | `/api/datasets/{id}/preview` | 数据预览 |
| DELETE | `/api/datasets/{id}` | 删除数据集 |
| POST | `/api/chat` | SSE 流式分析对话 |
| GET/DELETE | `/api/sessions` | 会话管理 |
| POST | `/api/knowledge/entry` | 录入知识（指标/术语/模板） |
| GET | `/api/knowledge` | 知识列表与统计 |
| POST | `/api/knowledge/search` | 知识混合检索测试 |
| DELETE | `/api/knowledge/{id}` | 删除知识条目 |

## 配置切换

- **Embedding**：默认本地 ONNX（零外部依赖），详见 [docs/LOCAL_DEPLOYMENT.md](docs/LOCAL_DEPLOYMENT.md)
- **元数据库改为 SQLite**：设置环境变量 `METADATA_TYPE=sqlite`（默认 h2）
- **关闭向量库**：设置 `ZVEC_ENABLED=false`
- **数据目录**：设置 `DATA_DIR`（默认 `./data`，相对后端工作目录）

## 文档

- [知识库设计](docs/KNOWLEDGE_BASE.md)：存储、检索、双写一致性、Rerank、知识图谱
- [知识图谱实现细节与改进方案](docs/KNOWLEDGE_GRAPH.md)：数据模型、构建流程、BFS 查询、已知限制与 GraphRAG 演进方案
- [面试题与参考答案](docs/INTERVIEW_QUESTIONS.md)：基于本项目的 13 道面试题（架构、图谱、检索、一致性、降级、前端）
- [本地部署](docs/LOCAL_DEPLOYMENT.md)：bge-small-zh-v1.5 ONNX 模型下载与配置
- [分析领域 DDD 模型](docs/DDD_ANALYSIS_MODEL.md)：枚举值对象与 period 缺陷修复

## 项目结构

```
data-analysis-agent/
├── frontend/          # React 前端
│   └── src/
│       ├── App.tsx              # 主应用与 SSE 交互
│       ├── api.ts               # API 客户端
│       ├── components/
│       │   ├── DatasetPanel.tsx    # 数据集管理
│       │   ├── AnalysisMessage.tsx # 分析结果渲染
│       │   └── AnalysisChart.tsx   # ECharts 图表
│       └── types.ts
└── backend/           # Spring Boot 后端
    ├── deploy.sh              # 部署脚本
    └── src/main/java/com/dataagent/
        ├── config/            # 数据源、Zvec、Web 配置
        ├── controller/        # REST + SSE API
        ├── service/           # 分析引擎、编排、向量、规则降级
        ├── repository/        # DuckDB / H2 双库访问
        ├── llm/               # LLM 策略接口 + DeepSeek 实现
        └── model/             # 领域模型
```
