import type { Dataset, ChatSession, SSEEvent, KnowledgeEntry, KnowledgeInput, KnowledgeHit } from './types';

const BASE = '/api';

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, init);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  health: () => json<Record<string, unknown>>('/health'),

  // 可用模型列表与默认模型 / Available chat models and the default model
  models: async (): Promise<{ models: Array<{ modelId: string; name: string }>; defaultModel: string }> =>
    json<{ models: Array<{ modelId: string; name: string }>; defaultModel: string }>('/models'),

  // ---- 知识库 / Knowledge base ----
  listKnowledge: async (): Promise<{ entries: KnowledgeEntry[]; stats: Array<Record<string, unknown>> }> =>
    json<{ entries: KnowledgeEntry[]; stats: Array<Record<string, unknown>> }>('/knowledge'),

  addKnowledge: async (input: KnowledgeInput): Promise<KnowledgeEntry> =>
    (await json<{ entry: KnowledgeEntry }>('/knowledge/entry', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    })).entry,

  searchKnowledge: async (query: string, kind?: string, topK = 5): Promise<KnowledgeHit[]> =>
    (await json<{ hits: KnowledgeHit[] }>('/knowledge/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, kind, topK }),
    })).hits,

  getKnowledgeDetail: async (id: string): Promise<Record<string, unknown>> =>
    (await json<{ detail: Record<string, unknown> }>(`/knowledge/${id}/detail`)).detail,

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
  getGraph: () => json<Record<string, unknown>>('/knowledge/graph'),
  rebuildGraph: () => json<Record<string, unknown>>('/knowledge/graph/rebuild', { method: 'POST' }),
  graphNeighbors: (nodeId: string, hops = 1) =>
    json<Record<string, unknown>>(`/knowledge/graph/neighbors?nodeId=${encodeURIComponent(nodeId)}&hops=${hops}`),

  // ---- Zvec 数据查看 / Zvec inspection ----
  zvecCollections: () => json<Record<string, unknown>>('/zvec/collections'),
  zvecDocs: (name: string, limit = 50) =>
    json<Record<string, unknown>>(`/zvec/collections/${name}/docs?limit=${limit}`),
  zvecDoc: (name: string, id: string) =>
    json<Record<string, unknown>>(`/zvec/collections/${name}/docs/${encodeURIComponent(id)}`),

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
      const blocks = buffer.split('\n\n');
      buffer = blocks.pop() || '';
      for (const block of blocks) {
        const lines = block.split('\n');
        const eventLine = lines.find(l => l.startsWith('event:'));
        const dataLine = lines.find(l => l.startsWith('data:'));
        if (!dataLine) continue;
        try {
          const data = JSON.parse(dataLine.slice(5).trim());
          const type = eventLine ? eventLine.slice(6).trim() : 'text';
          // Named SSE events carry their payload directly in `data`, so map
          // them into the field the UI expects (data.type holds the intent,
          // not the SSE event type).
          let event: SSEEvent;
          switch (type) {
            case 'plan':
              event = { type: 'plan', plan: data } as SSEEvent;
              break;
            case 'result':
              event = { type: 'result', result: data } as SSEEvent;
              break;
            case 'chart':
              event = { type: 'chart', chart: data } as SSEEvent;
              break;
            default:
              event = { type, ...(typeof data === 'object' ? data : { content: data }) } as SSEEvent;
          }
          onEvent(event);
        } catch {
          // ignore malformed SSE block
        }
      }
    }
  },
};
