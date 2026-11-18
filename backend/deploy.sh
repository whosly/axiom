#!/bin/bash
set -e

# 数据分析 Agent - 常驻服务部署脚本
# 用法: ./deploy.sh [start|stop|restart|status|build]

APP_NAME="data-analysis-agent"
JAR_PATH="target/data-analysis-agent-1.0.0.jar"
PID_FILE="app.pid"
LOG_FILE="app.log"
PROFILE=${SPRING_PROFILES_ACTIVE:-prod}

build() {
  echo "[build] 构建 jar..."
  mvn -q clean package -DskipTests
  echo "[build] 完成: $JAR_PATH"
}

start() {
  if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    echo "[start] 服务已在运行 (PID $(cat $PID_FILE))"
    return
  fi
  if [ ! -f "$JAR_PATH" ]; then
    echo "[start] jar 不存在，先构建..."
    build
  fi
  echo "[start] 启动服务 (profile=$PROFILE)..."
  nohup java -jar "$JAR_PATH" \
    --spring.profiles.active="$PROFILE" \
    >> "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  echo "[start] 已启动 (PID $(cat $PID_FILE))，日志: $LOG_FILE"
}

stop() {
  if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
      echo "[stop] 停止服务 (PID $PID)..."
      kill "$PID"
      rm -f "$PID_FILE"
    else
      echo "[stop] 进程已不存在"
      rm -f "$PID_FILE"
    fi
  else
    echo "[stop] 无 PID 文件"
  fi
}

status() {
  if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
    echo "[status] 运行中 (PID $(cat $PID_FILE))"
    curl -s http://localhost:8080/api/health || true
  else
    echo "[status] 未运行"
  fi
}

case "$1" in
  build)   build ;;
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 2; start ;;
  status)  status ;;
  *)       echo "用法: $0 {build|start|stop|restart|status}" ;;
esac
