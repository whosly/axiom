#!/usr/bin/env bash
# Axiom - 数据备份与恢复脚本
# Axiom - data backup and restore script
#
# 用法 / Usage:
#   ./scripts/backup.sh create [输出目录]       # 创建备份（默认 ./backups）
#   ./scripts/backup.sh list                    # 列出已有备份
#   ./scripts/backup.sh restore <备份文件>       # 从备份恢复（需停止服务）
#   ./scripts/backup.sh prune [保留数量]         # 清理旧备份（默认保留 5 份）
#
# 备份内容：H2/DuckDB/SQLite 元数据与分析库、Zvec 双 collection 向量、本地模型。
# Backup scope: H2/DuckDB/SQLite databases, Zvec collections and local models.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
VOLUME_NAME="axiom-data"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }

# 判定数据存储位置：容器 volume 或本地 backend/data
# Detect storage location: Docker volume or local backend/data.
resolve_data_source() {
  if docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
    echo "docker:$VOLUME_NAME"
  elif [ -d backend/data ]; then
    echo "local:backend/data"
  else
    err "未找到数据源 / No data source found"; exit 1
  fi
}

do_create() {
  # 第二参数始终是输出目录（与用法说明一致）/ Second argument is always an output directory.
  local target_dir="${2:-$BACKUP_DIR}"
  mkdir -p "$target_dir"
  local out="$target_dir/backup-$TIMESTAMP.tar.gz"
  local src
  src="$(resolve_data_source)"

  info "创建备份 / Creating backup: $out"
  case "$src" in
    docker:*)
      # 用临时容器打包 volume 数据到目标目录 / Archive volume into the target directory via a temp container.
      local vol="${src#docker:}"
      docker run --rm -v "$vol:/data:ro" -v "$(cd "$target_dir" && pwd):/backup" \
        alpine:3.20 tar czf "/backup/backup-$TIMESTAMP.tar.gz" -C /data . \
        || { err "备份失败 / Backup failed"; exit 1; }
      ;;
    local:*)
      local dir="${src#local:}"
      tar czf "$out" -C "$dir" . || { err "备份失败 / Backup failed"; exit 1; }
      ;;
  esac
  # Docker 模式下文件由容器内写到挂载目录，这里校验真实落盘路径后再报告。
  # In Docker mode the container writes to the mounted dir; verify the real path before reporting.
  [ -f "$out" ] || { err "备份文件未生成 / Backup file not found: $out"; exit 1; }
  info "备份完成 / Backup created: $out ($(du -h "$out" | cut -f1))"
}

do_list() {
  mkdir -p "$BACKUP_DIR"
  info "已有备份 / Available backups:"
  ls -lh "$BACKUP_DIR"/*.tar.gz 2>/dev/null || echo "  无备份 / No backups"
}

do_restore() {
  local file="${2:-}"
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    err "请指定有效的备份文件 / Specify a valid backup file"; exit 1
  fi
  local src
  src="$(resolve_data_source)"

  warn "恢复将覆盖现有数据，请先停止服务！/ Restore overwrites current data; stop the service first!"
  read -r -p "确认继续？[y/N] / Continue? [y/N] " ans
  [ "$ans" = "y" ] || [ "$ans" = "Y" ] || { echo "已取消 / Cancelled"; exit 0; }

  case "$src" in
    docker:*)
      local vol="${src#docker:}"
      # 用 cd+pwd 代替 realpath（旧版 macOS 无 realpath）；文件名经环境变量传入，避免空格拼接出错。
      # Use cd+pwd instead of realpath (absent on older macOS); pass filename via env to survive spaces.
      local file_dir="$(cd "$(dirname "$file")" && pwd)"
      local file_name="$(basename "$file")"
      docker run --rm -v "$vol:/data" -v "$file_dir:/backup:ro" -e BAK_FILE="$file_name" \
        alpine:3.20 sh -c 'find /data -mindepth 1 -delete && tar xzf "/backup/$BAK_FILE" -C /data' \
        || { err "恢复失败 / Restore failed"; exit 1; }
      ;;
    local:*)
      local dir="${src#local:}"
      # find -delete 覆盖 rm -rf dir/* 漏掉的点文件，恢复前彻底清空目标
      # / find -delete also removes dotfiles that rm -rf dir/* would miss.
      find "${dir:?}" -mindepth 1 -delete && tar xzf "$file" -C "$dir" || { err "恢复失败 / Restore failed"; exit 1; }
      ;;
  esac
  info "恢复完成 / Restored from: $file"
  info "请重启服务 / Restart the service: ./scripts/docker-deploy.sh restart"
}

do_prune() {
  local keep="${2:-5}"
  info "保留最近 $keep 份备份 / Keeping the latest $keep backups"
  find "$BACKUP_DIR" -name '*.tar.gz' -type f | sort -r | tail -n +$((keep + 1)) | while read -r f; do
    warn "删除 / Removing: $f"
    rm -f "$f"
  done
  info "清理完成 / Pruned"
}

case "${1:-}" in
  create) do_create "$@" ;;
  list)   do_list ;;
  restore) do_restore "$@" ;;
  prune)  do_prune "$@" ;;
  *)
    echo "用法 / Usage: $0 {create [dir]|list|restore <file>|prune [keep]}"
    exit 1
    ;;
esac
