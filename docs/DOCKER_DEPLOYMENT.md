# Docker 一键部署指南 / Docker One-Click Deployment

> 一条命令完成：前端编译 → 后端打包 → 镜像构建 → 容器启动 → 健康检查。
> One command does: frontend build → backend package → image build → container start → health check.

## 1. 前置要求 / Prerequisites

| 项 | 要求 |
|---|---|
| Docker | 20.10+ |
| Docker Compose | v2+ |
| 磁盘 | ≥ 3GB（基础镜像 + 依赖 + 约 90MB 模型 + 数据）|
| 内存 | ≥ 2GB |
| 公网 HTTPS（可选）| 域名已解析到服务器，80/443 端口开放 / Domain pointing to server, ports 80/443 open |

无需本地安装 JDK / Maven / Node —— 全部在镜像内完成。
No local JDK/Maven/Node needed — everything happens inside the build.

## 2. 一键启动 / Quick Start

```bash
cd data-analysis-agent

# 一键部署：构建 + 启动 + 等待健康检查通过
./docker-deploy.sh deploy

# 访问 / Access
# http://localhost:8080
```

`deploy` 是默认命令，直接执行 `./docker-deploy.sh` 效果相同。
`deploy` is the default command; running `./docker-deploy.sh` alone has the same effect.

脚本会自动等待 `/api/health` 通过并输出应用地址。
The script waits for `/api/health` and prints the ready URL.

## 3. 脚本命令 / Script Commands

```bash
./docker-deploy.sh deploy        # 一键部署（构建 + 启动 + 健康检查）
./docker-deploy.sh build         # 仅构建镜像（前端+后端+模型）
./docker-deploy.sh start         # 用已有镜像启动（跳过构建，更快）
./docker-deploy.sh stop          # 停止容器（保留数据卷）
./docker-deploy.sh restart       # 重启容器并等待健康检查
./docker-deploy.sh logs          # 查看日志
./docker-deploy.sh logs -f       # 实时跟踪日志
./docker-deploy.sh status        # 状态与健康检查
./docker-deploy.sh clean         # 删除容器与镜像（保留数据卷）
./docker-deploy.sh prod          # 公网 HTTPS 部署（见第 7 节）
./docker-deploy.sh push          # 构建并推送镜像到 REGISTRY（见第 8 节）
./docker-deploy.sh reset-vectors # 清除 Zvec 向量数据（兼容性故障恢复，见第 11 节）
```

## 4. 配置 / Configuration

复制模板并修改（脚本会自动加载 `.env`，Compose 也会将其注入容器）：
Copy the template and edit (the script and Compose both load `.env` automatically):

```bash
cp .env.example .env
```

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_PORT` | `8080` | 宿主机 HTTP 端口 / Host port |
| `JAVA_OPTS` | `-Xmx1g` | JVM 参数 / JVM options |
| `MODEL_MIRROR` | `hf-mirror.com` | 模型下载镜像 / Model mirror |
| `MAVEN_MIRROR` | 空 | 可选 Maven 镜像，弱网加速 / Optional Maven mirror for slow networks |
| `DEEPSEEK_API_KEY` | 空 | 可选，不填自动降级规则引擎 / Optional, rule fallback if empty |
| `EMBEDDING_PROVIDER` | `local` | `local`（默认）或 `api` |
| `RERANK_PROVIDER` | `auto` | `auto` / `llm` / `rule` |
| `KNOWLEDGE_CONTEXT_CHAR_BUDGET` | `2000` | 知识上下文字符预算 / Knowledge context budget |
| `DOMAIN` | `localhost` | prod 部署域名 / Domain for prod |
| `HTTPS_PORT` / `HTTP_PORT` | `443` / `80` | prod 对外端口 / Public ports |
| `REGISTRY` | 空 | push 命令的镜像仓库 / Registry for push |

例 / Example：
```bash
APP_PORT=9090 JAVA_OPTS=-Xmx2g DEEPSEEK_API_KEY=sk-xxx ./docker-deploy.sh deploy
```

## 5. 多阶段构建说明 / Multi-stage Build

```
Stage 1  node:20
         └─ npm ci + npm run build → dist/

Stage 2  maven:3.9-eclipse-temurin-17
         └─ 复制 pom / 源码 / dist/ 到 static/
         └─ mvn package → app.jar（依赖一次解析，构建更快）

Stage 3  eclipse-temurin:17-jre
         └─ curl 下载 bge-small-zh-v1.5 模型（带重试）
         └─ app.jar + JRE + 模型（最终镜像）
```

最终镜像约 500MB（JRE + jar + 模型 + 基础层）。
Final image is roughly 500MB (JRE + jar + model + base layers).

基础镜像可通过 build arg 替换 / Base images are overridable via build args：
```bash
docker compose build --build-arg JRE_IMAGE=eclipse-temurin:17-jre-alpine
```

## 6. 数据持久化 / Data Persistence

运行时数据通过 Docker volume `data-agent-data` 挂载到 `/app/data`：
Runtime data is mounted at `/app/data` via volume `data-agent-data`：

```
/app/data/
├── h2/metadata.mv.db           元数据 / Metadata
├── duckdb/analysis.duckdb      原始数据集 / Raw datasets
├── zvec/dataset_semantics/     数据集语义向量 / Dataset vectors
├── zvec/knowledge_base/        知识库向量 / Knowledge vectors
└── models/bge-small-zh-v1.5/   本地 embedding 模型 / Local model
```

- `docker compose down` **不会删除**数据卷 / does **not** remove the volume
- `docker compose down -v` 会删除数据卷（谨慎）/ **will** remove the volume (caution)
- `./docker-deploy.sh clean` 只删容器和镜像，保留数据卷 / keeps the volume

## 7. 公网 HTTPS 部署 / Public HTTPS Deployment

```bash
# 配置域名与端口 / Configure domain and ports
cat > .env <<'EOF'
DOMAIN=analytics.example.com
DEEPSEEK_API_KEY=sk-xxx
EOF

# 一键启动 HTTPS 反向代理 / One-click HTTPS reverse proxy
./docker-deploy.sh prod
```

脚本行为 / What the script does：

1. 加载 `docker-compose.prod.yml` overlay，Nginx 监听 80/443
2. 将后端端口改为仅 `127.0.0.1` 暴露，公网流量必须经 Nginx
3. 若 `nginx/certs/` 下没有证书，自动生成自签名证书（仅用于测试）
4. 构建、启动并等待 HTTPS 健康检查通过

证书配置 / Certificate setup：

- **测试**：脚本自动生成自签名证书，浏览器会提示不安全
- **生产**：将 Let's Encrypt 等 CA 签发的 `fullchain.pem` 和 `privkey.pem` 放入 `nginx/certs/`，重新执行 `./docker-deploy.sh prod`

Nginx 配置要点 / Nginx notes：

- `/api/chat` 单独配置 `proxy_buffering off` 和 300s 读超时，保证 SSE 流式分析实时到达前端
  (`/api/chat` disables proxy buffering and raises read timeout so SSE events stream in real time)
- 启用 HSTS、隐藏版本号、限制请求体 50MB
- ACME challenge 路径已预留，便于后续接入 certbot 自动续签

访问 / Access：`https://analytics.example.com`

## 8. 推送镜像到仓库 / Push Image to Registry

```bash
REGISTRY=registry.example.com/team ./docker-deploy.sh push
```

推送后可登录目标服务器拉取运行：
After pushing, on the target server:

```bash
docker pull registry.example.com/team/data-analysis-agent:latest
docker run -d -p 8080:8080 -v agent-data:/app/data --restart unless-stopped \
  registry.example.com/team/data-analysis-agent:latest
```

## 9. 健康检查 / Health Check

基础部署 / Basic deployment：
```bash
curl http://localhost:8080/api/health
# {"status":"ok",...}
```

HTTPS 部署 / HTTPS deployment：
```bash
curl -k https://localhost:443/api/health
```

## 10. 安全提示 / Security Notes

应用本身无鉴权（单用户设计），公网暴露前建议：
The app has no authentication (single-user design). Before public exposure:

- 使用 `prod` 命令通过 Nginx 终止 HTTPS，不要直接暴露 8080
- 配置防火墙限制来源 IP，或在 Nginx 上增加 Basic Auth
- 如需多用户，先实现权限隔离（见 ROADMAP P3）

## 11. 故障排查 / Troubleshooting

| 现象 | 原因与处理 / Cause and fix |
|---|---|
| 构建卡在 Maven 下载 | 设置 `MAVEN_MIRROR=https://maven.aliyun.com/repository/public` 后重新 `deploy` |
| 构建卡在模型下载 | 换 `MODEL_MIRROR` 源，或按下方离线方案手动放模型 |
| 容器反复重启 | `./docker-deploy.sh logs` 看异常；若日志出现 `SIGSEGV` + `libzvec_c_api.so`，见下方向量兼容处理 |
| 容器因 Zvec 崩溃（SIGSEGV）| 旧版本向量数据格式不兼容；执行 `./docker-deploy.sh reset-vectors && ./docker-deploy.sh restart`，业务数据保留，向量重建 |
| 健康检查失败 | 端口被占用改 `APP_PORT`；首次加载慢等待 90s；prod 模式用 `curl -k` |
| SSE 无实时输出 | 确认走的是 Nginx `/api/chat` location（已关闭 buffering）|
| 数据丢失 | 确认没用 `down -v`；`docker volume inspect data-agent-data` |
| 想重新构建 | `./docker-deploy.sh clean && ./docker-deploy.sh deploy` |

离线部署 / Offline deployment：构建期需要网络下载 Node 依赖、Maven 依赖与模型，运行期完全离线。
若构建机无法访问 hf-mirror，手动下载 `model.onnx` 和 `vocab.txt` 放到
`backend/data/models/bge-small-zh-v1.5/`，再把 Dockerfile 中的 `RUN curl ...` 改为 `COPY backend/data/models/... /app/data/models/...`。
