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
  // table 是 LLM 可输出的合法类型；引擎多数场景会转 bar，但契约上必须覆盖。
  // / 'table' is a valid LLM-planned type; the engine usually converts it to bar,
  // but the contract must cover it.
  type: 'line' | 'bar' | 'pie' | 'scatter' | 'table';
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
  chunkIndex?: number;
  textHash?: string;
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

// ---- 知识图谱 / Knowledge graph ----

/**
 * 图谱节点：后端用 JdbcTemplate.queryForList 序列化，H2 场景产出大写键（ID/TYPE/LABEL），
 * 其他场景为小写键，视图层统一经下方归一工具读取，不直接访问字段。
 * / Graph node: the backend serializes via JdbcTemplate.queryForList, which yields
 * uppercase keys on H2 and lowercase elsewhere; read through the normalizers below.
 */
export interface GraphNode {
  id?: string; ID?: string;
  type?: string; TYPE?: string;
  label?: string; LABEL?: string;
}

/** 图谱边：同 GraphNode 的键大小写差异 / Graph edge (same key-case caveat as GraphNode). */
export interface GraphEdge {
  source?: string; SOURCE?: string;
  target?: string; TARGET?: string;
  relation?: string; RELATION?: string;
}

/** GET /knowledge/graph 响应 / Graph payload. */
export interface GraphPayload {
  nodes: GraphNode[];
  edges: GraphEdge[];
  stats?: Record<string, unknown>;
}

/** 邻居子图响应 / Neighbor subgraph response. */
export interface GraphNeighborsPayload extends GraphPayload {
  center: string;
  hops: number;
}

/** 图谱社区发现结果 / Graph community detection result. */
export interface GraphCommunity {
  size: number;
  members: Array<{ id: string; label: string }>;
  typeDistribution: Record<string, number>;
}

/** 最短路径结果 / Shortest-path result. */
export interface GraphPathResult {
  path: Array<{ id: string; label: string; type: string }>;
  length: number;
  reachable: boolean;
  message?: string;
}

/** 关联条目 / Related entry. */
export interface GraphRelatedItem {
  id: string;
  label: string;
  sharedRelations: number;
}

/** 图谱响应归一工具 / Graph payload normalizers. */
export const nodeId = (n: GraphNode): string => String(n.id ?? n.ID ?? '');
export const nodeLabel = (n: GraphNode): string => String(n.label ?? n.LABEL ?? nodeId(n));
export const nodeType = (n: GraphNode): string => String(n.type ?? n.TYPE ?? 'knowledge');
export const edgeSource = (e: GraphEdge): string => String(e.source ?? e.SOURCE ?? '');
export const edgeTarget = (e: GraphEdge): string => String(e.target ?? e.TARGET ?? '');
export const edgeRelation = (e: GraphEdge): string => String(e.relation ?? e.RELATION ?? '');

// ---- Zvec 检视 / Zvec inspector ----

/** Zvec collection 统计 / Zvec collection summary. */
export interface ZvecCollection {
  name: string;
  docCount?: number;
  indexCount?: number;
  ready?: boolean;
}

/**
 * Zvec 文档：字段与 collection schema 相关（knowledge/document 两种 schema），
 * 保留索引签名以透传任意字段，已知字段显式声明。
 * / Zvec doc: fields depend on the collection schema; known fields are declared and an
 * index signature passes unknown fields through for the JSON detail view.
 */
export interface ZvecDoc {
  id?: string;
  title?: string;
  kind?: string;
  embedding?: string;
  [key: string]: unknown;
}

/** 处理链路步骤 / Lineage step. */
export interface KnowledgeLineageStep {
  step?: string;
  stage?: string;
  detail?: string;
  [key: string]: unknown;
}

/** 知识条目详情（详情抽屉响应）/ Knowledge entry detail (detail-dialog response). */
export interface KnowledgeDetail {
  entry?: KnowledgeEntry;
  sourceLabel?: string;
  datasetName?: string;
  vector?: { present?: boolean; dimension?: number; contentLength?: number };
  lineage?: KnowledgeLineageStep[];
  storage?: Record<string, unknown>;
  [key: string]: unknown;
}
