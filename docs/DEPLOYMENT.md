# 部署与扩展 / Deployment & Extension

> 涵盖本地 jar、Docker 一键部署、公网 HTTPS、Embedding 模型与 LLM 厂商扩展。实现版本：2026-11-23。

## 目录

- [1. 部署方式选择](#1-部署方式选择)
- [2. 本地 jar 部署](#2-本地-jar-部署)
- [3. Docker 一键部署](#3-docker-一键部署)
- [4. 公网 HTTPS 部署](#4-公网-https-部署)
- [5. 配置参考](#5-配置参考)
- [6. Embedding 模型](#6-embedding-模型)
- [7. LLM 厂商与模型扩展](#7-llm-厂商与模型扩展)
- [8. 数据持久化与备份](#8-数据持久化与备份)
- [9. 验证与故障排查](#9-验证与故障排查)

---

## 1. 部署方式选择

| 方式 | 适用场景 | 前置条件 |
|---|---|---|
| Docker 一键 | 推荐。含前端编译、后端打包、模型下载、健康检查 | Docker 20.10+ / Compose v2.24+（用了 `env_file` 的 `path/required` 语法），磁盘 ≥3GB、内存 ≥2GB |
| 本地 jar | 已有 JDK/Maven/Node，或需要直接调试 | JDK 17+ / Maven 3.6+ / Node 18+ |

两种方式都无需外部 API Key 即可运行：默认本地 ONNX embedding，无 LLM Key 时自动降级规则引擎。

---

## 2. 本地 jar 部署

```bash
# 1) 前端构建并复制到后端静态目录
cd frontend && npm install && npm run build
cd .. && rm -rf backend/src/main/resources/static/assets
cp -r frontend/dist/* backend/src/main/resources/static/

# 2) 后端打包
cd backend && mvn clean package -DskipTests

# 3) 启动（从项目根目录执行常驻脚本）
cd .. && ./scripts/local-deploy.sh start
# 或前台：cd backend && java -jar target/axiom-1.0.0.jar
```

`local-deploy.sh` 支持 `start / stop / restart / status / build`，PID 与日志写在 `backend/` 下。访问 `http://localhost:8080`。

首次启动后建议初始化向量索引，详见 [第 6 节](#6-embedding-模型)。

---

## 3. Docker 一键部署

```bash
cd axiom
cp .env.example .env        # 可选：填入 API Key 等配置
./scripts/docker-deploy.sh deploy
```

`deploy` 是默认命令，一条命令完成：前端编译 → 后端打包 → 镜像构建 → 容器启动 → 健康检查，脚本会等待 `/api/health` 通过。

脚本命令：

| 命令 | 说明 |
|---|---|
| `deploy` | 一键部署（构建 + 启动 + 健康检查） |
| `build` | 仅构建镜像（前端 + 后端 + 模型） |
| `start` | 用已有镜像启动（跳过构建） |
| `stop` / `restart` | 停止 / 重启并等待健康检查 |
| `logs [-f]` | 查看日志 / 实时跟踪 |
| `status` | 状态与健康检查 |
| `clean` | 删除容器与镜像（保留数据卷） |
| `prod` | 公网 HTTPS 部署（见第 4 节） |
| `push` | 构建并推送镜像到 `REGISTRY` |
| `reset-vectors` | 清除 Zvec 向量数据（兼容性故障恢复） |

### 多阶段构建

```
Stage 1  node:20
         └─ npm ci + npm run build → dist/

Stage 2  maven:3.9-eclipse-temurin-17
         └─ 复制 pom / 源码 / dist/ 到 static/
         └─ mvn package → app.jar（依赖一次解析，构建更快）

Stage 3  eclipse-temurin:17-jre
         └─ curl 下载 bge-small-zh-v1.5 模型（带重试）
         └─ app.jar + JRE + 模型（最终镜像约 500MB）
```

基础镜像可通过 build arg 替换（注意运行时阶段用了 `apt-get`，**不能换成 Alpine 变体**）：

```bash
docker compose build --build-arg JRE_IMAGE=eclipse-temurin:17-jre
```

弱网构建：设置 `MAVEN_MIRROR=https://maven.aliyun.com/repository/public` 加速 Maven 依赖；模型下载失败则换 `MODEL_MIRROR`，或按 [第 6 节](#6-embedding-模型) 手动放模型后改用 `COPY`。构建期需要网络，运行期完全离线。

---

## 4. 公网 HTTPS 部署

```bash
cat > .env <<'EOF'
DOMAIN=analytics.example.com
DEEPSEEK_API_KEY=sk-xxx
EOF

./scripts/docker-deploy.sh prod
```

脚本行为：加载 `docker-compose.prod.yml` overlay，Nginx 监听 80/443；后端端口改为仅 `127.0.0.1` 暴露，公网流量必须经 Nginx；若 `nginx/certs/` 无证书则生成自签名证书（仅测试用）；构建、启动并等待 HTTPS 健康检查通过。

证书：测试用自签名；生产把 CA 签发的 `fullchain.pem` 和 `privkey.pem` 放入 `nginx/certs/`，重新执行 `prod`。

Nginx 要点：`/api/chat` 单独配置 `proxy_buffering off` 和 300s 读超时保证 SSE 实时到达；启用 HSTS、隐藏版本号、限制请求体 50MB；ACME challenge 路径已预留。

**安全提示**：应用本身无鉴权（单用户设计），公网暴露前不要直接开放 8080，应通过 Nginx 终止 HTTPS，并配置防火墙限制来源 IP 或加 Basic Auth。多用户隔离见 ROADMAP（明确不做）。

---

## 5. 配置参考

复制 `cp .env.example .env`，脚本与 Compose 都会自动加载。

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_PORT` | `8080` | 宿主机 HTTP 端口 |
| `JAVA_OPTS` | `-Xmx1g` | JVM 参数 |
| `LLM_PROVIDER` | `deepseek` | `deepseek` / `qwen` |
| `DEEPSEEK_API_KEY` | 空 | 可选，不填降级规则引擎 |
| `QWEN_API_KEY` | 空 | 通义千问（百炼 OpenAI 兼容模式） |
| `EMBEDDING_PROVIDER` | `local` | `local` / `api` |
| `EMBEDDING_API_KEY` | 空 | 仅 `api` 模式需要 |
| `ZVEC_ENABLED` | `true` | 关闭向量库 |
| `ZVEC_DIMENSION` | `512` | 向量维度，须与 embedding 一致 |
| `RERANK_PROVIDER` | `auto` | `auto` / `llm` / `rule` |
| `KNOWLEDGE_CONTEXT_CHAR_BUDGET` | `2000` | 注入 prompt 的知识字符预算 |
| `METADATA_TYPE` | `h2` | `h2` / `sqlite` |
| `DATA_DIR` | `./data` | 数据目录（相对后端工作目录） |
| `GRAPH_MAX_NODES` | `10000` | 图谱采样阈值 |
| `GRAPH_SAMPLE_SIZE` | `3000` | 采样保留节点数 |
| `MODEL_MIRROR` | `hf-mirror.com` | 模型下载镜像 |
| `MAVEN_MIRROR` | 空 | 可选 Maven 镜像 |
| `DOMAIN` | `localhost` | prod 部署域名 |
| `HTTP_PORT` / `HTTPS_PORT` | `80` / `443` | prod 对外端口 |
| `REGISTRY` | 空 | `push` 命令的镜像仓库 |

例：

```bash
APP_PORT=9090 JAVA_OPTS=-Xmx2g DEEPSEEK_API_KEY=sk-xxx ./scripts/docker-deploy.sh deploy
```

配置文件位于 `backend/src/main/resources/application.yml`，所有厂商共享 `app.llm.providers.<name>.*` 结构。运行时可在前端「配置」面板热切换厂商与模型，无需重启——厂商与模型选择会写入 `data/llm-config.json`（API Key 不落盘），重启后仍生效。

---

## 6. Embedding 模型

默认用 **ONNX Runtime + bge-small-zh-v1.5** 在 CPU 上推理，零网络、零 Key。

```
文本 → BertTokenizer(vocab.txt) → input_ids / attention_mask / token_type_ids
     → ONNX Runtime(model.onnx) → last_hidden_state[CLS]
     → L2 归一化 → 512 维向量 → Zvec
```

模型位于 `backend/data/models/bge-small-zh-v1.5/`：

| 文件 | 大小 | 说明 |
|---|---|---|
| `model.onnx` | ~90MB | bge-small-zh-v1.5 的 ONNX 推理模型 |
| `vocab.txt` | 107KB | BERT WordPiece 词表（21128 token） |

官方 BAAI 仓库无 ONNX，使用 HuggingFace 的 Xenova 转换版。首次下载：

```bash
cd backend/data/models/bge-small-zh-v1.5
curl -L -o vocab.txt 'https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/vocab.txt'
curl -L -o model.onnx 'https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx'

file model.onnx          # 应为 data / protobuf，非 HTML
wc -l vocab.txt          # 应为 21128
```

模型缺失时系统仍能启动，但向量写入失败（状态 `vector_failed`），检索降级为关键词。模型是**懒加载**的（首次 embed 才载入），启动后执行：

```bash
curl -X POST 'http://localhost:8080/api/knowledge/reindex?force=true'
# 期望：{"total":8,"success":8,"failed":0}
curl http://localhost:8080/api/knowledge/status
curl http://localhost:8080/api/zvec/collections
```

切换模型导致维度变化时，必须删除旧 collection 并重建：

```bash
rm -rf backend/data/zvec/dataset_semantics backend/data/zvec/knowledge_base_v2
# 重启应用后再 reindex
```

本地 ONNX 与远程 API 的对比：

| 维度 | 本地 ONNX（默认） | 远程 API |
|---|---|---|
| 外部依赖 | 无 | SiliconFlow + API Key |
| 网络 | 运行期离线 | 每次 embed 都需网络 |
| 隐私 | 数据不出本机 | 文本发往第三方 |
| 向量维度 | 512 | 由 `EMBEDDING_DIMENSION` 决定（默认 512） |
| 首次延迟 | 模型加载 ~2 秒 | 网络往返 |

切回远程：设 `EMBEDDING_PROVIDER=api` 并配 `EMBEDDING_API_KEY`。请求会显式传 `dimensions=EMBEDDING_DIMENSION`；若服务商不支持该参数且返回维度不符，应用会报错而非静默截断——此时需把 `EMBEDDING_DIMENSION`、`ZVEC_DIMENSION` 同时改为模型实际维度并重建 collection。

---

## 7. LLM 厂商与模型扩展

厂商与模型的接入、切换、热配置 API 与扩展点见独立文档：[LLM 厂商与模型扩展指南](LLM_PROVIDER_GUIDE.md)。

- 同厂商换模型：只改配置，零代码
- 新增 OpenAI 兼容厂商：1 个 Provider 类 + 1 个配置块，约 5 行代码
- 新增非 OpenAI 协议厂商：实现 `LlmProvider` 接口

---

## 8. 数据持久化与备份

Docker 用 volume `axiom-data` 挂载到 `/app/data`，本地用 `backend/data/`：

```
/app/data/
├── h2/metadata.mv.db           元数据
├── duckdb/analysis.duckdb      原始数据集
├── zvec/dataset_semantics/     数据集语义向量
├── zvec/knowledge_base_v2/     知识库向量（含 FTS 索引）
└── models/bge-small-zh-v1.5/   本地 embedding 模型
```

`docker compose down` **不会**删除数据卷；`docker compose down -v` **会**删除（谨慎）；`clean` 只删容器和镜像，保留数据卷。

```bash
./scripts/backup.sh create              # 创建备份到 ./backups/
./scripts/backup.sh list                # 列出备份
./scripts/backup.sh restore <备份文件>   # 恢复（先停服务，需确认）
./scripts/backup.sh prune 5             # 仅保留最近 5 份
```

备份脚本自动识别 Docker 数据卷或本地 `backend/data`，包含全部数据库、向量与模型。

---

## 9. 验证与故障排查

健康检查：

```bash
curl http://localhost:8080/api/health
# {"status":"ok","llmProvider":"deepseek"}

curl -k https://localhost:443/api/health   # HTTPS 部署
```

一键生成前后端覆盖率报告（输出到 `reports/coverage/`，不受 `mvn clean` 影响）：

```bash
./scripts/coverage.sh
./scripts/coverage.sh --open    # 生成后打开汇总页
```

| 现象 | 原因与处理 |
|---|---|
| 构建卡在 Maven 下载 | 设置 `MAVEN_MIRROR=https://maven.aliyun.com/repository/public` 后重新 `deploy` |
| 构建卡在模型下载 | 换 `MODEL_MIRROR`，或手动放模型后改 Dockerfile 为 `COPY` |
| 容器反复重启 | `./scripts/docker-deploy.sh logs` 看异常 |
| Zvec 崩溃（SIGSEGV + `libzvec_c_api.so`） | 旧向量数据格式不兼容；`reset-vectors && restart`，业务数据保留 |
| 健康检查失败 | 端口占用改 `APP_PORT`；首次加载慢等待 90s；prod 用 `curl -k` |
| SSE 无实时输出 | 确认走 Nginx `/api/chat` location（已关闭 buffering） |
| 数据丢失 | 确认没用 `down -v`；`docker volume inspect axiom-data` |
| reindex 返回 failed | 检查模型文件存在、`ZVEC_DIMENSION=512`、collection 按 512 重建 |
| 检索结果分数偏低 | 本地 bge-small-zh 配合 rerank；无 Key 时用规则 rerank，分数是相对值 |

推送镜像到仓库：

```bash
REGISTRY=registry.example.com/team ./scripts/docker-deploy.sh push

# 目标服务器
docker pull registry.example.com/team/axiom:latest
docker run -d -p 8080:8080 -v axiom-data:/app/data --restart unless-stopped \
  registry.example.com/team/axiom:latest
```
