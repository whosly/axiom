#!/usr/bin/env bash
# 数据分析 Agent - 本地 jar 常驻部署脚本（无需 Docker）
# Data Analysis Agent - local jar daemon deployment script (no Docker required)
#
# 与 scripts/docker-deploy.sh 的分工：本脚本直接在宿主机用 nohup java -jar 运行，
# 需要本地 JDK 17 与 Maven；Docker 方案见 scripts/docker-deploy.sh。
# Division of labour: this script runs the jar directly via nohup java -jar on the host
# and needs local JDK 17 + Maven; for the Docker approach see scripts/docker-deploy.sh.
#
# 用法 / Usage:
#   ./scripts/local-deploy.sh build     # 仅打包 jar / Build jar only
#   ./scripts/local-deploy.sh start     # 后台启动 / Start in background
#   ./scripts/local-deploy.sh stop      # 停止 / Stop
#   ./scripts/local-deploy.sh restart   # 重启 / Restart
#   ./scripts/local-deploy.sh status    # 状态与健康检查 / Status and health check
#
# 可设置 SPRING_PROFILES_ACTIVE 切换 profile / Set SPRING_PROFILES_ACTIVE to switch profile.

set -e

# 脚本位于 scripts/，先切到 backend 再操作 jar / Script lives in scripts/; cd to backend before touching the jar.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/backend"

APP_NAME="data-analysis-agent"
JAR_PATH="target/data-analysis-agent-1.0.0.jar"
PID_FILE="app.pid"
LOG_FILE="app.log"
PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"

# 构建可执行 jar / Build the executable jar
build() {
  echo "[build] 构建 jar / Building jar..."
  mvn -q clean package -DskipTests
  echo "[build] 完成 / Done: $JAR_PATH"
}

# 后台启动服务，已运行则跳过 / Start in background; skip if already running
start() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "[start] 服务已在运行 / Already running (PID $(cat "$PID_FILE"))"
    return
  fi
  if [ ! -f "$JAR_PATH" ]; then
    echo "[start] jar 不存在，先构建 / Jar missing, building first..."
    build
  fi
  echo "[start] 启动服务 / Starting (profile=$PROFILE)..."
  nohup java -jar "$JAR_PATH" \
    --spring.profiles.active="$PROFILE" \
    >> "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "[start] 已启动 / Started (PID $(cat "$PID_FILE"))，日志 / log: $LOG_FILE"
}

# 按 PID 文件停止服务 / Stop the service using the PID file
stop() {
  if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
      echo "[stop] 停止服务 / Stopping (PID $PID)..."
      kill "$PID"
      rm -f "$PID_FILE"
    else
      echo "[stop] 进程已不存在 / Process not found"
      rm -f "$PID_FILE"
    fi
  else
    echo "[stop] 无 PID 文件 / No PID file"
  fi
}

# 查看进程与健康状态 / Show process and health status
status() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "[status] 运行中 / Running (PID $(cat "$PID_FILE"))"
    curl -s http://localhost:8080/api/health || true
  else
    echo "[status] 未运行 / Not running"
  fi
}

case "${1:-status}" in
  build)   build ;;
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 2; start ;;
  status)  status ;;
  -h|--help|help) echo "用法 / Usage: $0 {build|start|stop|restart|status}" ;;
  *)       echo "用法 / Usage: $0 {build|start|stop|restart|status}"; exit 1 ;;
esac
