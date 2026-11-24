import type {
  Dataset, ChatSession, SSEEvent, KnowledgeEntry, KnowledgeInput, KnowledgeHit,
  KnowledgeDetail, GraphPayload, GraphNeighborsPayload, GraphCommunity, GraphPathResult,
  GraphRelatedItem, ZvecCollection, ZvecDoc,
} from './types';
import { parseSSEBlock } from './sse';

const BASE = '/api';

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, init);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${res.status}`);
  }
  // 204 / 空响应体不是错误；返回空对象让调用方解构得到 undefined 而非抛 SyntaxError。
  // 204 or an empty body is not an error; return {} so callers destructure safely.
  const text = await res.text();
  if (!text.trim()) return {} as T;
  return JSON.parse(text) as T;
}

export const api = {
  // 可用模型列表与默认模型 / Available chat models and the default model
  models: async (): Promise<{ models: Array<{ modelId: string; name: string }>; defaultModel: string }> =>
    json<{ models: Array<{ modelId: string; name: string }>; defaultModel: string }>('/models'),

  // 运行时 LLM 配置 / Runtime LLM configuration
  getLlmConfig: () => json<Record<string, unknown>>('/config/llm'),
  updateLlmConfig: (config: Record<string, string>) =>
    json<Record<string, unknown>>('/config/llm', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(config),
    }),

  // ---- 知识库 / Knowledge base ----
  listKnowledge: async (): Promise<{ entries: KnowledgeEntry[]; stats: Array<Record<string, unknown>> }> =>
    json<{ entries: KnowledgeEntry[]; stats: Array<Record<string, unknown>> }>('/knowledge'),

  addKnowledge: async (input: KnowledgeInput): Promise<KnowledgeEntry> =>
    (await json<{ entry: KnowledgeEntry }>('/knowledge/entry', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    })).entry,

  searchKnowledge: async (query: string, kind?: string, topK = 5, datasetId?: string): Promise<KnowledgeHit[]> =>
    (await json<{ hits: KnowledgeHit[] }>('/knowledge/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, kind, topK, datasetId }),
    })).hits,

  getKnowledgeDetail: async (id: string): Promise<KnowledgeDetail> =>
    (await json<{ detail: KnowledgeDetail }>(`/knowledge/${id}/detail`)).detail,

  // 上传文档入库（PDF/DOCX/MD/TXT）/ Ingest a document into the knowledge base
  ingestDocument: async (file: File, datasetId?: string): Promise<Record<string, unknown>> => {
    const fd = new FormData();
    fd.append('file', file);
    if (datasetId) fd.append('datasetId', datasetId);
    return json<Record<string, unknown>>('/knowledge/document', { method: 'POST', body: fd });
  },

  knowledgeStatus: () => json<Record<string, unknown>>('/knowledge/status'),
  reindexKnowledge: (force = false) =>
    json<Record<string, unknown>>(`/knowledge/reindex?force=${force}`, { method: 'POST' }),

  // ---- 知识图谱 / Knowledge graph ----
  getGraph: () => json<GraphPayload>('/knowledge/graph'),
  rebuildGraph: () => json<Record<string, unknown>>('/knowledge/graph/rebuild', { method: 'POST' }),
  graphNeighbors: (nodeId: string, hops = 1) =>
    json<GraphNeighborsPayload>(`/knowledge/graph/neighbors?nodeId=${encodeURIComponent(nodeId)}&hops=${hops}`),

  // 图谱推理 / Graph reasoning
  graphCommunities: (maxIterations = 20) =>
    json<{ communities: GraphCommunity[]; count: number; iterations: number;
      sampled?: boolean; totalNodes?: number; analyzedNodes?: number; version?: number }>(
      `/knowledge/graph/reason/communities?maxIterations=${maxIterations}`),
  graphPath: (from: string, to: string) =>
    json<GraphPathResult>(
      `/knowledge/graph/reason/path?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  graphRelated: (entryId: string, limit = 10) =>
    json<{ related: GraphRelatedItem[]; count: number }>(
      `/knowledge/graph/reason/related?entryId=${encodeURIComponent(entryId)}&limit=${limit}`),

  // ---- 知识来源 / Knowledge sources ----
  listSources: async (): Promise<Array<{
    id: string; name: string; type: string; description?: string;
    entryCount: number; status: string; createdAt?: string; updatedAt?: string;
  }>> => (await json<{ sources: Array<{
    id: string; name: string; type: string; description?: string;
    entryCount: number; status: string; createdAt?: string; updatedAt?: string;
  }> }>('/knowledge/sources')).sources,
  deleteSource: (sourceId: string) =>
    json<{ success: boolean; deleted: number }>(`/knowledge/sources/${encodeURIComponent(sourceId)}`,
      { method: 'DELETE' }),

  // ---- JDBC 数据源 / External datasources ----
  listDatasources: async (): Promise<Array<{
    id: string; name: string; type: string; jdbcUrl: string; username: string;
    status: string; lastTestedAt?: string; createdAt: string;
  }>> => (await json<{ datasources: Array<{
    id: string; name: string; type: string; jdbcUrl: string; username: string;
    status: string; lastTestedAt?: string; createdAt: string;
  }> }>('/datasources/jdbc')).datasources,
  registerDatasource: (data: { name: string; type: string; jdbcUrl: string; username: string; password?: string }) =>
    json<{ datasource: Record<string, unknown> }>('/datasources/jdbc', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
    }),
  testDatasource: (id: string) =>
    json<{ success: boolean; catalog?: string; majorVersion?: number; error?: string }>(
      `/datasources/jdbc/${encodeURIComponent(id)}/test`, { method: 'POST' }),
  importDatasource: (id: string, data: { sql: string; limit?: number; name?: string }) =>
    json<{ datasetId: string; rows: number; columns: number }>(
      `/datasources/jdbc/${encodeURIComponent(id)}/import`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
      }),
  deleteDatasource: (id: string) =>
    json<{ success: boolean }>(`/datasources/jdbc/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  // ---- Zvec 数据查看 / Zvec inspection ----
  zvecCollections: () => json<{ collections: ZvecCollection[] }>('/zvec/collections'),
  zvecDocs: (name: string, limit = 50) =>
    json<{ docs: ZvecDoc[] }>(`/zvec/collections/${name}/docs?limit=${limit}`),
  zvecDoc: (name: string, id: string) =>
    json<{ doc: Record<string, unknown> }>(`/zvec/collections/${name}/docs/${encodeURIComponent(id)}`),

  deleteKnowledge: (id: string) =>
    json<{ success: boolean }>(`/knowledge/${id}`, { method: 'DELETE' }),

  listDatasets: async (): Promise<Dataset[]> =>
    (await json<{ datasets: Dataset[] }>('/datasets')).datasets,

  getDataset: async (id: string): Promise<Dataset> =>
    (await json<{ dataset: Dataset }>(`/datasets/${id}`)).dataset,

  createSample: async (): Promise<Dataset> =>
    (await json<{ dataset: Dataset }>('/datasets/sample', { method: 'POST' })).dataset,

  upload: async (file: File): Promise<Dataset> => {
    const fd = new FormData();
    fd.append('file', file);
    return (await json<{ dataset: Dataset }>('/datasets/upload', { method: 'POST', body: fd })).dataset;
  },

  paste: async (text: string, name: string): Promise<Dataset> =>
    (await json<{ dataset: Dataset }>('/datasets/paste', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, name }),
    })).dataset,

  preview: async (id: string, limit = 20): Promise<Array<Record<string, unknown>>> =>
    (await json<{ rows: Array<Record<string, unknown>> }>(`/datasets/${id}/preview?limit=${limit}`)).rows,

  deleteDataset: (id: string) =>
    json<{ success: boolean }>(`/datasets/${id}`, { method: 'DELETE' }),

  listSessions: async (): Promise<ChatSession[]> =>
    (await json<{ sessions: ChatSession[] }>('/sessions')).sessions,

  getSession: async (id: string): Promise<ChatSession> =>
    (await json<{ session: ChatSession }>(`/sessions/${id}`)).session,

  deleteSession: (id: string) =>
    json<{ success: boolean }>(`/sessions/${id}`, { method: 'DELETE' }),

  renameSession: (id: string, title: string) =>
    json<{ success: boolean }>(`/sessions/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    }),

  chat: async (
    payload: { sessionId?: string; datasetId: string; message: string; model: string },
    onEvent: (event: SSEEvent) => void,
    signal?: AbortSignal,
  ): Promise<void> => {
    const res = await fetch(BASE + '/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal,
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `HTTP ${res.status}`);
    }
    const reader = res.body?.getReader();
    if (!reader) throw new Error('无法读取响应流');
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // SSE 规范允许 \n\n 或 \r\n\r\n；正则同时匹配两种分隔符。
      // SSE allows both LF and CRLF event separators; split on either form.
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || '';
      for (const block of blocks) {
        const event = parseSSEBlock(block);
        if (event) onEvent(event);
      }
    }
    // 流结束时必须无参 decode 冲出 TextDecoder 缓存的多字节残留：
    // stream:true 模式下跨 chunk 截断的 UTF-8 尾字节会一直留在解码器里，
    // 不 flush 则中文最后一个字符静默丢失。/ A no-arg decode() flushes the decoder's
    // buffered tail bytes; without it a multi-byte char split across the final chunk is lost.
    buffer += decoder.decode();
    // 流结束时缓冲区可能还有最后一个未以空行结尾的事件 / Flush trailing block without a final blank line.
    if (buffer.trim()) {
      const event = parseSSEBlock(buffer);
      if (event) onEvent(event);
    }
  },
};
