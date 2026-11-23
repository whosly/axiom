#!/usr/bin/env bash
# Axiom 一键测试覆盖率脚本 / One-click test coverage script
#
# 跑前后端全部单元测试，报告固定输出到 reports/coverage/，不受 mvn clean 影响。
# Run all unit tests and write reports to reports/coverage/ so they survive mvn clean.
#
# 用法 / Usage:
#   ./scripts/coverage.sh           # 生成报告 / Generate reports
#   ./scripts/coverage.sh --open    # 生成后打开汇总页 / Open summary afterwards

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPORT_DIR="$ROOT_DIR/reports/coverage"
BACKEND_REPORT="$REPORT_DIR/backend"
FRONTEND_REPORT="$REPORT_DIR/frontend"
mkdir -p "$BACKEND_REPORT" "$FRONTEND_REPORT"

info() { printf '\033[0;32m[INFO]\033[0m %s\n' "$1"; }

# 后端：JaCoCo 经 pom.xml 配置直接输出到 reports/coverage/backend
# Backend: JaCoCo writes to reports/coverage/backend via the pom.xml configuration.
info "后端测试 / Backend tests..."
(cd backend && mvn -q test)

# 前端：清空 IDE 注入的 NODE_OPTIONS，避免 shim 文件混入覆盖率统计
# Frontend: clear IDE-injected NODE_OPTIONS so shims are excluded from coverage.
info "前端测试 / Frontend tests..."
(cd frontend && NODE_OPTIONS="" npm run test:coverage) > "$FRONTEND_REPORT/coverage.txt" 2>&1

# 从 JaCoCo XML 提取行覆盖率 / Extract line coverage from the JaCoCo XML report
BACKEND_COUNTER=$(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "$BACKEND_REPORT/jacoco.xml" 2>/dev/null | tail -1 || true)
BACKEND_PCT="n/a"
if [ -n "$BACKEND_COUNTER" ]; then
  MISSED=$(printf '%s' "$BACKEND_COUNTER" | sed -E 's/.*missed="([0-9]+)".*/\1/')
  COVERED=$(printf '%s' "$BACKEND_COUNTER" | sed -E 's/.*covered="([0-9]+)".*/\1/')
  BACKEND_PCT=$(awk -v m="$MISSED" -v c="$COVERED" 'BEGIN { t=m+c; if (t>0) printf "%.1f%%", c*100/t; else print "n/a" }')
fi

# 从 Node 文本报告提取 all files 行覆盖率 / Extract frontend line coverage from the Node text report
FRONTEND_PCT=$(grep '^# all files' "$FRONTEND_REPORT/coverage.txt" | awk -F'|' '{gsub(/ /,"",$2); print $2"%"}' | tail -1 || true)
FRONTEND_PCT="${FRONTEND_PCT:-n/a}"

# 生成汇总页，集中给出报告入口与关键指标 / Generate a summary with report links and key metrics
{
  echo "# Axiom 测试覆盖率 / Test Coverage"
  echo ""
  echo "生成时间 / Generated: $(date '+%Y-%m-%d %H:%M:%S')"
  echo ""
  echo "| 端 / Side | 行覆盖率 / Lines | 报告 / Reports |"
  echo "|---|---|---|"
  echo "| 后端 / Backend | $BACKEND_PCT | [HTML](backend/index.html) · [XML](backend/jacoco.xml) |"
  echo "| 前端 / Frontend | $FRONTEND_PCT | [明细](frontend/coverage.txt) |"
  echo ""
  echo "## 前端源文件明细 / Frontend source detail"
  echo ""
  echo '```text'
  grep '^# src/' "$FRONTEND_REPORT/coverage.txt" | grep -v '__tests__' | sed 's/^# //' || true
  echo '```'
} > "$REPORT_DIR/SUMMARY.md"

info "报告已生成 / Reports generated:"
info "  $REPORT_DIR/SUMMARY.md"
info "  $BACKEND_REPORT/index.html"
info "  $FRONTEND_REPORT/coverage.txt"

if [ "${1:-}" = "--open" ]; then
  open "$REPORT_DIR/SUMMARY.md" 2>/dev/null || true
fi
