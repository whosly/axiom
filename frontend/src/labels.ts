import { api } from './api';

/**
 * 领域枚举标签 / Domain enum labels.
 *
 * <p>标签的单一事实来源是后端枚举（GET /api/labels）；本地常量仅在
 * API 不可达时兜底，避免两端映射漂移。/ Backend enums are the single source of
 * truth (GET /api/labels); local constants only serve as an offline fallback.
 */

const FALLBACK_INTENT_LABELS: Record<string, string> = {
  query: '数据查询',
  trend: '趋势分析',
  anomaly: '异常识别',
  compare: '指标对比',
  summary: '数据概览',
};

let intentLabels: Record<string, string> = { ...FALLBACK_INTENT_LABELS };
let aggregations: Record<string, string> = {};

/** 取意图标签 / Intent label lookup. */
export function intentLabel(value: string): string {
  return intentLabels[value] ?? value;
}

/** 取聚合方式标签 / Aggregation label lookup. */
export function aggregationLabel(value: string): string {
  return aggregations[value] ?? value;
}

/** 启动时从后端拉取标签映射，失败保留本地兜底 / Fetch label maps at startup; keep fallback on failure. */
export async function loadLabels(): Promise<void> {
  try {
    const l = await api.labels();
    if (l.intents && Object.keys(l.intents).length > 0) {
      intentLabels = { ...intentLabels, ...l.intents };
    }
    if (l.aggregations && Object.keys(l.aggregations).length > 0) {
      aggregations = { ...aggregations, ...l.aggregations };
    }
  } catch {
    // 后端不可达时静默使用兜底映射 / Silent fallback when the backend is unreachable.
  }
}
