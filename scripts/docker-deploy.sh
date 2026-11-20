#!/usr/bin/env bash
# 数据分析 Agent - Docker 一键部署脚本
# Data Analysis Agent - Docker one-click deployment script
#
# 一条命令完成：前端编译 → 后端打包 → 镜像构建 → 容器启动 → 健康检查
# One command does: frontend build → backend package → image build → container start → health check
#
# 用法 / Usage:
#   ./scripts/docker-deploy.sh deploy        # 一键部署（构建 + 启动 + 健康检查，默认命令）
#   ./scripts/docker-deploy.sh build         # 仅构建镜像（前端编译 + 后端打包 + 模型下载）
#   ./scripts/docker-deploy.sh start         # 用已有镜像启动（不重新构建）
#   ./scripts/docker-deploy.sh stop          # 停止容器（保留数据卷）
#   ./scripts/docker-deploy.sh restart       # 重启容器
#   ./scripts/docker-deploy.sh logs [-f]     # 查看日志（-f 实时跟踪）
#   ./scripts/docker-deploy.sh status        # 查看状态与健康检查
#   ./scripts/docker-deploy.sh clean         # 停止并删除容器与镜像（保留数据卷）
#   ./scripts/docker-deploy.sh prod          # 公网部署：HTTPS 反向代理（需先配置域名与证书）
#   ./scripts/docker-deploy.sh push          # 构建并推送镜像到 REGISTRY（可选）
#   ./scripts/docker-deploy.sh reset-vectors # 清除 Zvec 向量数据（旧版本数据不兼容导致启动崩溃时）
#
# 环境变量 / Environment variables（可用 .env 文件或 shell export）：
#   APP_PORT          宿主机 HTTP 端口，默认 8080
#   JAVA_OPTS         JVM 参数，默认 -Xmx1g
#   MODEL_MIRROR      模型下载镜像源
#   REGISTRY          镜像仓库地址，push 命令使用，如 registry.example.com/team
#   DOMAIN            prod 命令使用的域名，默认 localhost
#   HTTPS_PORT        prod HTTPS 端口，默认 443
#   HTTP_PORT         prod HTTP 端口（重定向到 HTTPS），默认 80

set -euo pipefail

# 脚本位于 scripts/，始终切换到项目根目录，保证 compose 的相对路径（nginx/、.env）有效
# Script lives in scripts/; always cd to the project root so compose relative paths (nginx/, .env) resolve.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# 加载 .env（若存在）：让脚本与 Compose 使用同一份配置
# Load .env (if present) so script and Compose share the same configuration.
if [ -f .env ]; then
  # shellcheck disable=SC1091
  set -a && . ./.env && set +a
fi

COMPOSE="docker compose"
IMAGE="${IMAGE:-data-analysis-agent:latest}"
CONTAINER="data-analysis-agent"
APP_PORT="${APP_PORT:-8080}"
DOMAIN="${DOMAIN:-localhost}"
HTTPS_PORT="${HTTPS_PORT:-443}"
HTTP_PORT="${HTTP_PORT:-80}"
REGISTRY="${REGISTRY:-}"

# 是否启用公网 HTTPS overlay / Whether to enable public HTTPS overlay
USE_PROD=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }

# 组装 Compose 命令：基础文件 + 可选 HTTPS overlay
# Build Compose command: base file + optional HTTPS overlay.
compose() {
  local -a files=(-f docker-compose.yml)
  if [ "$USE_PROD" -eq 1 ]; then
    files+=(-f docker-compose.prod.yml)
  fi
  $COMPOSE "${files[@]}" "$@"
}

check_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    err "Docker 未安装 / Docker not installed"
    exit 1
  fi
  if ! $COMPOSE version >/dev/null 2>&1; then
    err "Docker Compose 不可用（需要 v2+）/ Docker Compose v2+ required"
    exit 1
  fi
}

# 前端编译 + 后端打包 + 模型下载都在 Dockerfile 多阶段构建内完成
# Frontend build, backend package and model download all happen inside the multi-stage Dockerfile.
do_build() {
  check_docker
  info "构建镜像（前端编译 + 后端打包 + 模型下载）..."
  info "Building image (frontend compile + backend package + model download)..."
  warn "首次构建会下载 Node/Maven 依赖和约 90MB 模型，请耐心等待 / First build downloads dependencies and ~90MB model"
  # --progress plain 让日志可持续输出，便于 CI / 后台构建排查
  # --progress plain keeps logs streaming, which is friendly to CI and background builds.
  compose build --pull --progress plain
  info "镜像构建完成 / Image built: $IMAGE"
}

do_up() {
  compose up -d
}

# 等待应用就绪：优先用 curl 轮询 /api/health，容器异常退出时提前失败
# Wait for readiness via /api/health; fail early if the container exited unexpectedly.
wait_health() {
  local url="http://localhost:${APP_PORT}/api/health"
  local label="应用 / App"
  if [ "$USE_PROD" -eq 1 ]; then
    url="https://localhost:${HTTPS_PORT}/api/health"
    label="公网应用 / Public app"
  fi

  info "等待健康检查 / Waiting for health check: $url"
  for i in $(seq 1 60); do
    if curl -kfsS "$url" >/dev/null 2>&1; then
      info "${label}就绪 / Ready: ${url%/api/health}"
      compose ps
      return 0
    fi
    # 容器已退出则不再空等 / Stop waiting if the container has exited
    if [ "$(compose ps -q --status exited 2>/dev/null | wc -l | tr -d ' ')" != "0" ]; then
      err "容器异常退出，请查看日志 / Container exited unexpectedly: $0 logs"
      compose ps
      return 1
    fi
    sleep 3
  done
  warn "健康检查超时，请查看日志 / Health check timeout: $0 logs"
  compose ps
  return 1
}

do_deploy() {
  do_build
  do_up
  wait_health
}

do_start() {
  check_docker
  info "用已有镜像启动容器 / Starting with existing image..."
  do_up
  wait_health
}

do_stop() {
  compose down
  info "已停止（数据卷保留）/ Stopped (data volume kept)"
}

do_restart() {
  compose restart
  info "已重启 / Restarted"
  wait_health
}

do_logs() {
  if [ "${2:-}" = "-f" ]; then
    compose logs -f --tail=100
  else
    compose logs --tail=200
  fi
}

do_status() {
  compose ps
  echo ""
  local url="http://localhost:${APP_PORT}/api/health"
  [ "$USE_PROD" -eq 1 ] && url="https://localhost:${HTTPS_PORT}/api/health"
  if curl -kfsS "$url" 2>/dev/null; then
    echo ""
    info "健康检查通过 / Health check OK"
  else
    warn "应用未响应 / App not responding: $url"
  fi
}

do_clean() {
  warn "将删除容器与镜像（数据卷保留）/ Removing containers and images (data volume kept)"
  compose down --rmi local
  info "清理完成 / Cleaned"
}

do_prod() {
  check_docker
  info "公网部署：构建并启用 HTTPS 反向代理 / Public deploy: build with HTTPS reverse proxy"
  info "域名 / Domain: $DOMAIN  HTTP: $HTTP_PORT  HTTPS: $HTTPS_PORT"
  mkdir -p nginx/certs
  if [ ! -f nginx/certs/fullchain.pem ] || [ ! -f nginx/certs/privkey.pem ]; then
    warn "未找到证书，将生成自签名证书 / No cert found, generating self-signed cert"
    openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
      -keyout nginx/certs/privkey.pem -out nginx/certs/fullchain.pem \
      -subj "/CN=${DOMAIN}" >/dev/null 2>&1
    info "已生成自签名证书（生产环境请替换为 Let's Encrypt 正式证书）"
    info "Self-signed cert generated (replace with a real CA cert in production)"
  fi
  do_build
  do_up
  wait_health
}

# 重置向量数据：保留 H2/DuckDB 业务数据，只删除 Zvec collection
# Reset vectors: keep H2/DuckDB business data, remove only Zvec collections.
# 旧版本 Zvec 数据格式不兼容时会触发 native SIGSEGV，需清理后让应用重建。
# Incompatible legacy Zvec data can cause a native SIGSEGV; recreate collections after cleanup.
do_reset_vectors() {
  check_docker
  local vol="data-agent-data"
  warn "将删除数据卷 $vol 中的 Zvec 向量数据（业务数据保留）/ Removing Zvec vectors from volume $vol (business data kept)"
  docker run --rm -v "${vol}:/app/data" alpine:3.20 \
    sh -c 'rm -rf /app/data/zvec && echo "zvec data removed"'
  info "向量数据已清除，重启后自动重建 / Vectors cleared, will be rebuilt on restart"
  info "执行 / Run: $0 restart"
}

do_push() {
  check_docker
  if [ -z "$REGISTRY" ]; then
    err "请先设置 REGISTRY 环境变量 / Set REGISTRY first, e.g. REGISTRY=registry.example.com/team"
    exit 1
  fi
  do_build
  local remote="${REGISTRY}/data-analysis-agent:latest"
  info "打标签并推送 / Tagging and pushing: $remote"
  docker tag "$IMAGE" "$remote"
  docker push "$remote"
  info "推送完成 / Pushed: $remote"
}

usage() {
  echo "用法 / Usage: $0 {deploy|build|start|stop|restart|logs [-f]|status|clean|prod|push|reset-vectors}"
  echo ""
  echo "  deploy        一键部署（构建 + 启动 + 健康检查）/ One-click deploy"
  echo "  build         仅构建镜像 / Build image only"
  echo "  start         用已有镜像启动 / Start with existing image"
  echo "  stop          停止容器 / Stop"
  echo "  restart       重启容器 / Restart"
  echo "  logs          查看日志 / View logs"
  echo "  status        状态与健康检查 / Status and health check"
  echo "  clean         删除容器与镜像 / Remove containers and images"
  echo "  prod          公网 HTTPS 部署 / Public HTTPS deployment"
  echo "  push          推送镜像到 REGISTRY / Push image to registry"
  echo "  reset-vectors 清除 Zvec 向量数据（兼容性故障恢复）/ Reset Zvec vectors"
}

case "${1:-deploy}" in
  deploy)  do_deploy ;;
  build)   do_build ;;
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_restart ;;
  logs)    do_logs "$@" ;;
  status)  do_status ;;
  clean)   do_clean ;;
  prod)    USE_PROD=1; do_prod ;;
  push)    do_push ;;
  reset-vectors) do_reset_vectors ;;
  -h|--help|help) usage ;;
  *)
    err "未知命令 / Unknown command: $1"
    usage
    exit 1
    ;;
esac
