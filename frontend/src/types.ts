export interface ColumnInfo {
  name: string;
  originalName: string;
  type: 'numeric' | 'temporal' | 'categorical' | 'boolean';
  nullCount: number;
  sampleValues: string;
}

export interface Dataset {
  id: string;
  name: string;
  fileName: string;
  fileType: string;
  rowCount: number;
  columnCount: number;
  tableName: string;
  columns: ColumnInfo[];
  createdAt: string;
  updatedAt: string;
}

export interface AnalysisPlan {
  intent: string;
  metrics?: string[];
  aggregation?: string;
  dimension?: string;
  timeColumn?: string;
  timeGranularity?: string;
  filters?: Record<string, unknown>;
  sortBy?: string;
  sortOrder?: string;
  limit: number;
  chartType: string;
  title: string;
  reasoning?: string;
}

export interface ChartSpec {
  type: 'line' | 'bar' | 'pie' | 'scatter';
  xField: string;
  yFields: string[];
  title: string;
  series: Array<Record<string, unknown>>;
}

export interface AnalysisResult {
  type: string;
  title: string;
  conclusion?: string;
  insights?: string[];
  chart?: ChartSpec;
  rows: Array<Record<string, unknown>>;
  summary?: Record<string, unknown>;
  sql?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  model?: string;
  createdAt?: string;
  plan?: AnalysisPlan;
  result?: AnalysisResult;
  streaming?: boolean;
  stage?: string;
}

export interface ChatSession {
  id: string;
  title: string;
  datasetId: string;
  model: string;
  createdAt: string;
  updatedAt: string;
  messages: ChatMessage[];
}

export type SSEEvent =
  | { type: 'init'; sessionId: string; datasetId: string; userMessageId: string; model: string }
  | { type: 'status'; stage: string; message: string }
  | { type: 'plan'; plan: AnalysisPlan }
  | { type: 'result'; result: AnalysisResult }
  | { type: 'chart'; chart: ChartSpec }
  | { type: 'text'; content: string }
  | { type: 'done'; sessionId: string; messageId: string }
  | { type: 'error'; message: string };

/**
 * 知识条目 / Knowledge entry.
 * 与后端 KnowledgeEntry 对应，用于知识库管理界面展示。
 */
export interface KnowledgeEntry {
  id: string;
  kind: string;          // metric | glossary | recipe | document | case
  title: string;
  content: string;
  formula?: string;
  fields?: string[];
  tags?: string[];
  sourceType: string;    // manual | analysis | document | import
  sourceId: string;
  datasetId?: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 检索命中 / Retrieval hit with fused score. */
export interface KnowledgeHit {
  id: string;
  kind: string;
  title: string;
  content: string;
  sourceId: string;
  score: number;
}

/** 知识录入请求 / Knowledge entry creation request. */
export interface KnowledgeInput {
  kind: string;
  title: string;
  content: string;
  formula?: string;
  fields?: string[];
  tags?: string[];
  datasetId?: string;
}

export const KIND_LABELS: Record<string, string> = {
  metric: '指标口径',
  glossary: '业务术语',
  recipe: '分析模板',
  document: '文档',
  case: '历史案例',
};

export const INTENT_LABELS: Record<string, string> = {
  query: '数据查询',
  trend: '趋势分析',
  anomaly: '异常识别',
  compare: '指标对比',
  summary: '数据概览',
};

export const INTENT_COLORS: Record<string, string> = {
  query: '#0052d9',
  trend: '#00a870',
  anomaly: '#e34d59',
  compare: '#ed7b2f',
  summary: '#8e56ff',
};
