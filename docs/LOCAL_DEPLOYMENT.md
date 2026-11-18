# 本地部署指南 / Local Deployment Guide

> 目标：完全本地运行数据分析 Agent，**Embedding 不依赖任何外部服务**。
> Goal: run the app fully locally, with **zero external dependency for embeddings**.

## 1. 架构变化 / Architecture Change

原方案用 SiliconFlow 的 `/embeddings` API 生成向量，需要 `EMBEDDING_API_KEY`。
本地方案用 **ONNX Runtime + bge-small-zh-v1.5** 在 CPU 上直接推理，零网络、零 Key。

```
文本 → BertTokenizer(vocab.txt) → input_ids/attention_mask/token_type_ids
     → ONNX Runtime(model.onnx) → last_hidden_state[CLS]
     → L2 归一化 → 512 维向量 → Zvec
```

策略模式：`app.embedding.provider = local | api`，默认 `local`。

## 2. 环境要求 / Requirements

| 项 | 要求 |
|---|---|
| JDK | 17+ |
| Maven | 3.6+ |
| Node.js | 18+（仅构建前端 / only for frontend build）|
| 内存 | ≥ 2GB（ONNX 模型约 90MB + 推理缓冲）|
| 磁盘 | ≥ 500MB（模型 90MB + jar 203MB + 数据）|
| 网络 | 仅首次下载模型需要；运行期完全离线 / Only needed once to download model |

## 3. 模型文件 / Model Files

位置：`backend/data/models/bge-small-zh-v1.5/`

| 文件 | 大小 | 说明 |
|---|---|---|
| `model.onnx` | ~90MB | bge-small-zh-v1.5 的 ONNX 推理模型 |
| `vocab.txt` | 107KB | BERT WordPiece 词表（21128 token）|

模型输出 512 维向量，取 `[CLS]` 位置并 L2 归一化（BGE 标准做法）。

### 3.1 首次下载 / First-time Download

模型来自 HuggingFace 的 Xenova 转换版（官方 BAAI 仓库无 ONNX）。若 `huggingface.co` 不可达，用镜像 `hf-mirror.com`：

```bash
cd backend/data/models/bge-small-zh-v1.5

# vocab.txt
curl -L -o vocab.txt \
  'https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/vocab.txt'

# model.onnx（约 90MB）
curl -L -o model.onnx \
  'https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx'

# 校验 / Verify
file model.onnx          # 应为 data / protobuf，非 HTML
wc -l vocab.txt          # 应为 21128
```

> 若镜像也不可用，可从 ModelScope 或任意渠道获取同名文件放入该目录。
> 模型缺失时系统仍能启动，但向量写入会失败（状态 `vector_failed`），检索降级为关键词。

## 4. 配置 / Configuration

`backend/src/main/resources/application.yml` 关键段：

```yaml
app:
  embedding:
    provider: local          # local | api，默认 local
    local:
      model-path: ./data/models/bge-small-zh-v1.5/model.onnx
      vocab-path: ./data/models/bge-small-zh-v1.5/vocab.txt
      dimension: 512         # 必须与模型一致
      max-length: 512        # 最大序列长度
      threads: 2             # ONNX 推理线程数
  zvec:
    dimension: 512           # 必须与 embedding 维度一致
```

环境变量覆盖 / Env overrides：

| 变量 | 默认 | 说明 |
|---|---|---|
| `EMBEDDING_PROVIDER` | `local` | 设为 `api` 切回远程 |
| `EMBEDDING_MODEL_PATH` | `./data/models/.../model.onnx` | 模型路径 |
| `EMBEDDING_VOCAB_PATH` | `./data/models/.../vocab.txt` | 词表路径 |
| `EMBEDDING_THREADS` | `2` | 推理线程数 |
| `ZVEC_DIMENSION` | `512` | 向量维度 |

**重要**：切换模型导致维度变化时，必须删除旧 collection 目录并重建：
```bash
rm -rf backend/data/zvec/dataset_semantics backend/data/zvec/knowledge_base
# 然后重启应用，再 reindex
```

## 5. 构建与启动 / Build & Run

```bash
# 1) 构建前端
cd frontend
npm install
npm run build

# 2) 复制前端产物到后端静态目录
cd ..
rm -rf backend/src/main/resources/static/assets
cp -r frontend/dist/* backend/src/main/resources/static/

# 3) 构建后端 jar
cd backend
mvn clean package -DskipTests

# 4) 启动（常驻）
./deploy.sh start
# 或前台：java -jar target/data-analysis-agent-1.0.0.jar
```

访问 `http://localhost:8080`。

## 6. 首次初始化向量 / Initial Vectorization

本地模型是**懒加载**的（首次 embed 时才载入，避免拖慢启动）。启动后执行：

```bash
# 重建全部向量索引（本地模型生成，无需任何 Key）
curl -X POST 'http://localhost:8080/api/knowledge/reindex?force=true'

# 期望返回：{"total":8,"success":8,"failed":0}
# 查看状态
curl http://localhost:8080/api/knowledge/status
curl http://localhost:8080/api/zvec/collections
```

首次 reindex 会打印：
```
[Tokenizer] loaded 21128 tokens from .../vocab.txt
[LocalEmbedding] model loaded: .../model.onnx (dim=512, threads=2)
```

之后新增知识会自动生成本地向量，无需 reindex。

## 7. 验证清单 / Verification

```bash
# 健康检查（无需任何 Key）
curl http://localhost:8080/api/health

# 向量是否写入 Zvec（docCount 应 > 0）
curl http://localhost:8080/api/zvec/collections

# 混合检索（向量 + 全文 + RRF + Rerank）
curl -X POST http://localhost:8080/api/knowledge/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"GMV 怎么计算","topK":3}'

# 离线验证：断开网络后重复上述检索，仍应正常
```

界面验证：知识库页 →「Zvec 数据」Tab，可见文档列表与向量维度。

## 8. 数据目录 / Data Layout

```
backend/data/
├── models/bge-small-zh-v1.5/   # 本地模型（model.onnx + vocab.txt）
├── h2/metadata.mv.db           # 元数据
├── duckdb/analysis.duckdb      # 原始数据
└── zvec/
    ├── dataset_semantics/      # 字段/历史/结论向量
    └── knowledge_base/         # 知识向量
```

## 9. 与远程 API 方案对比 / Comparison

| 维度 | 本地 ONNX（本方案）| 远程 API |
|---|---|---|
| 外部依赖 | 无 | SiliconFlow + API Key |
| 网络 | 运行期离线 | 每次 embed 都需网络 |
| 隐私 | 数据不出本机 | 文本发往第三方 |
| 向量维度 | 512（bge-small-zh）| 1024（bge-m3）|
| 首次延迟 | 模型加载 ~2 秒 | 网络往返 |
| 单条耗时 | CPU 推理 ~几十 ms | 网络 ~几百 ms |

切换回远程：设 `EMBEDDING_PROVIDER=api` 并配 `EMBEDDING_API_KEY`，注意同时把 `ZVEC_DIMENSION` 改回 1024 并重建 collection。

## 10. 常见问题 / FAQ

**Q: reindex 返回 failed？**
A: 检查模型文件是否存在、维度配置是否 512、Zvec collection 是否按 512 重建。

**Q: 启动很慢？**
A: 本地模型是懒加载，启动不慢；首次 reindex 加载模型约 2 秒属正常。

**Q: 检索结果分数偏低？**
A: 本地 bge-small-zh 与 LLM rerank 配合；无 DeepSeek Key 时用规则 rerank，分数是归一化的相对值。

**Q: 能否换更大的模型？**
A: 可换 BGE-M3 ONNX（1024 维，~2GB），需同步改 `local.dimension` 和 `zvec.dimension` 并重建 collection。
