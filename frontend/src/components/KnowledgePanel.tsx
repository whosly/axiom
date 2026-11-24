import { Suspense, lazy, useEffect, useRef, useState } from 'react';
import { Button, Input, Textarea, Select, Tag, Dialog, Loading, Tooltip } from 'tdesign-react';
import { AddIcon, SearchIcon, DeleteIcon, BookIcon, BrowseIcon, RefreshIcon } from 'tdesign-icons-react';
import { api } from '../api';
import type { KnowledgeEntry, KnowledgeHit, KnowledgeInput, KnowledgeDetail } from '../types';
import { KIND_LABELS } from '../types';
// 图谱视图依赖 ECharts，按需加载避免进入首屏 bundle / Lazy-load the ECharts-based graph view.
const KnowledgeGraphView = lazy(() => import('./KnowledgeGraphView').then(m => ({ default: m.KnowledgeGraphView })));
import { GraphReasoningView } from './GraphReasoningView';
import { KnowledgeSourcesView } from './KnowledgeSourcesView';
import { ZvecInspectorView } from './ZvecInspectorView';
import { confirmDialog, notifyError, notifySuccess } from '../notify';

/**
 * 知识库管理面板 / Knowledge base management panel.
 * 支持录入、列表、检索测试和删除 / Supports entry creation, listing, retrieval test and deletion.
 */
interface Props {
  datasetId: string | null;
  onClose: () => void;
}

const KIND_OPTIONS = [
  { label: '指标口径', value: 'metric' },
  { label: '业务术语', value: 'glossary' },
  { label: '分析模板', value: 'recipe' },
];

export function KnowledgePanel({ datasetId, onClose }: Props) {
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [addOpen, setAddOpen] = useState(false);
  const [form, setForm] = useState<KnowledgeInput>({ kind: 'metric', title: '', content: '', formula: '' });
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<KnowledgeHit[] | null>(null);
  const [busy, setBusy] = useState(false);
  // 详情抽屉状态 / Detail drawer state
  const [detail, setDetail] = useState<KnowledgeDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  // 详情弹窗开关与数据分离：加载中弹窗保持打开（无需 {} as KnowledgeDetail 占位）。
  // / Dialog open flag decoupled from data: the dialog stays visible while loading,
  // no `{} as KnowledgeDetail` placeholder needed.
  const [detailOpen, setDetailOpen] = useState(false);
  // 视图切换：entries | graph | zvec / View switcher
  const [view, setView] = useState<'entries' | 'graph' | 'zvec' | 'sources'>('entries');
  // 图谱子视图：可视化 / 推理 / Graph sub-view: visualization or reasoning
  const [graphTab, setGraphTab] = useState<'visual' | 'reason'>('visual');
  // 文档上传与向量状态 / Document upload and vector status
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [statusStats, setStatusStats] = useState<Array<Record<string, unknown>>>([]);
  const [reindexing, setReindexing] = useState(false);

  // 请求序号守卫：并发 refresh（上传后/重建后/手动/切换数据集）只让最新一次生效，
  // 慢的旧响应直接丢弃，避免乱序覆盖。/ Sequence guard: only the latest refresh wins.
  const refreshSeq = useRef(0);

  const refresh = async () => {
    const seq = ++refreshSeq.current;
    setLoading(true);
    try {
      const entries = (await api.listKnowledge()).entries;
      // 过期响应（已被更新的请求超越）直接丢弃 / Drop stale responses.
      if (seq !== refreshSeq.current) return;
      // 条目列表先落地：状态统计失败不应连累已到手的条目不渲染。
      // / Land the entries first: a status-statistics failure must not withhold
      // the already-fetched entry list from rendering.
      setEntries(entries);
    } catch (e) {
      console.error(e);
      if (seq === refreshSeq.current) setLoading(false);
      return;
    }
    try {
      // 向量状态统计；H2 返回大写键 STATUS/COUNT，需兼容读取
      // / Vector status; H2 returns uppercase STATUS/COUNT keys.
      const status = await api.knowledgeStatus();
      if (seq !== refreshSeq.current) return;
      const raw = (status.statuses || status.stats || status.status || []) as Array<Record<string, unknown>>;
      // Array.isArray 防御：兼容字段最终落到非数组对象时静默降级，而不是抛错吞掉状态条。
      // / Defensive Array.isArray: degrade silently instead of throwing and losing the bar.
      setStatusStats(Array.isArray(raw) ? raw.map(s => ({
        status: s.status ?? s.STATUS,
        count: s.count ?? s.COUNT,
      })) : []);
    } catch (e) {
      // 状态统计独立失败只降级状态条 / A stats-only failure degrades just the status bar.
      console.error(e);
    }
    finally { if (seq === refreshSeq.current) setLoading(false); }
  };

  /** 上传文档入库并刷新 / Ingest an uploaded document and refresh. */
  const uploadDocument = async (file: File) => {
    setBusy(true);
    try {
      const result = await api.ingestDocument(file, datasetId || undefined);
      notifySuccess(`文档入库完成 / Document ingested：${result.chunks} 块 / chunks，${result.added} 条 / entries`);
      await refresh();
    } catch (e) { notifyError('文档入库失败 / Ingest failed: ' + (e as Error).message); }
    finally { setBusy(false); if (fileInputRef.current) fileInputRef.current.value = ''; }
  };

  /** 重建失败向量的索引 / Reindex entries with failed vectors. */
  const doReindex = async (force: boolean) => {
    setReindexing(true);
    try {
      const result = await api.reindexKnowledge(force);
      notifySuccess('向量重建完成 / Reindex done: ' + JSON.stringify(result));
      await refresh();
    } catch (e) { notifyError('向量重建失败 / Reindex failed: ' + (e as Error).message); }
    finally { setReindexing(false); }
  };

  // datasetId 变化时重新刷新：上传/检索都作用在当前数据集语境上，
  // 切换数据集后列表与向量状态需与语境一致。/ Reload when the dataset changes so
  // entries and vector status stay consistent with the active dataset context.
  useEffect(() => { refresh(); }, [datasetId]); // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async () => {
    if (!form.title.trim() || !form.content.trim()) return;
    setBusy(true);
    try {
      await api.addKnowledge({ ...form, fields: form.fields?.length ? form.fields : undefined });
      setAddOpen(false);
      setForm({ kind: 'metric', title: '', content: '', formula: '' });
      await refresh();
    } catch (e) { notifyError('录入失败: ' + (e as Error).message); }
    finally { setBusy(false); }
  };

  const doSearch = async () => {
    if (!query.trim()) return;
    setBusy(true);
    // 传入当前数据集，让检索测试台的结果与真实分析链路的知识范围一致。
    // Pass the active dataset so test results match what the analysis pipeline actually sees.
    try { setHits(await api.searchKnowledge(query, undefined, 5, datasetId || undefined)); }
    catch (e) { notifyError('检索失败: ' + (e as Error).message); }
    finally { setBusy(false); }
  };

  const remove = async (id: string) => {
    if (!(await confirmDialog('删除知识', '确定删除这条知识吗？'))) return;
    try {
      await api.deleteKnowledge(id);
      await refresh();
    } catch (e) {
      notifyError('删除失败: ' + (e as Error).message);
    }
  };

  // 打开详情：加载元数据+向量状态+处理链路 / Open detail: load metadata + vector status + lineage
  // 详情请求序号：快速连点两条知识时，慢的旧响应不得覆盖新响应、不得提前关闭 Loading。
  // / Detail sequence guard: a slow stale response must never overwrite the latest
  // click's data or hide the loading spinner prematurely.
  const detailSeq = useRef(0);

  const openDetail = async (id: string) => {
    const seq = ++detailSeq.current;
    setDetailOpen(true);
    setDetailLoading(true);
    setDetail(null);
    try {
      const d = await api.getKnowledgeDetail(id);
      if (seq !== detailSeq.current) return;
      setDetail(d);
    } catch (e) {
      if (seq !== detailSeq.current) return;
      notifyError('加载详情失败: ' + (e as Error).message);
      setDetailOpen(false);
    } finally {
      if (seq === detailSeq.current) setDetailLoading(false);
    }
  };

  return (
    <div className="h-full flex flex-col" style={{ backgroundColor: 'var(--td-bg-color-page)' }}>
      <header className="h-14 flex items-center justify-between px-5 border-b flex-shrink-0"
        style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
        <div className="flex items-center gap-2">
          <BookIcon size={20} />
          <span className="text-sm font-medium">知识库管理</span>
          <Tag size="small" variant="light">{entries.length} 条</Tag>
        </div>
        <div className="flex items-center gap-1">
          <Button size="small" variant={view === 'entries' ? 'base' : 'text'} onClick={() => setView('entries')}>知识条目</Button>
          <Button size="small" variant={view === 'graph' ? 'base' : 'text'}
            onClick={() => { setView('graph'); setGraphTab('visual'); }}>知识图谱</Button>
          <Button size="small" variant={view === 'graph' && graphTab === 'reason' ? 'base' : 'text'}
            onClick={() => { setView('graph'); setGraphTab('reason'); }}>图谱推理</Button>
          <Button size="small" variant={view === 'zvec' ? 'base' : 'text'} onClick={() => setView('zvec')}>Zvec 数据</Button>
          <Button size="small" variant={view === 'sources' ? 'base' : 'text'} onClick={() => setView('sources')}>来源</Button>
          {view === 'entries' && <>
            <Button size="small" variant="outline" icon={<AddIcon />} onClick={() => setAddOpen(true)}>录入知识</Button>
            <Button size="small" variant="outline" icon={<AddIcon />} loading={busy}
              onClick={() => fileInputRef.current?.click()}>导入文档</Button>
            <input ref={fileInputRef} type="file" accept=".pdf,.docx,.md,.txt" className="hidden"
              onChange={e => { const f = e.target.files?.[0]; if (f) uploadDocument(f); }} />
            <Tooltip content="重建失败向量；force=true 时重建全部">
              <Button size="small" variant="text" icon={<RefreshIcon />} loading={reindexing}
                onClick={() => doReindex(false)}>重建向量</Button>
            </Tooltip>
          </>}
          <Button size="small" variant="text" onClick={onClose}>返回对话</Button>
        </div>
      </header>

      {view === 'graph' ? (
        graphTab === 'reason' ? <GraphReasoningView /> :
        <Suspense fallback={<div className="flex justify-center py-16"><Loading /></div>}><KnowledgeGraphView /></Suspense>
      ) :
       view === 'zvec' ? <ZvecInspectorView /> :
       view === 'sources' ? <KnowledgeSourcesView /> :
      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-4xl mx-auto space-y-6">
          {/* 向量化状态概览 / Vectorization status overview */}
          {statusStats.length > 0 && (
            <div className="flex items-center gap-3 flex-wrap text-xs rounded-xl p-3 border"
              style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
              <span className="font-medium">向量状态 / Vector status</span>
              {statusStats.map((s, i) => (
                <Tag key={i} size="small" variant="light"
                  theme={String(s.status) === 'ready' ? 'success' : String(s.status) === 'vector_failed' ? 'danger' : 'warning'}>
                  {String(s.status)}: {String(s.count ?? 0)}
                </Tag>
              ))}
              <Button size="small" variant="text" icon={<RefreshIcon />} loading={reindexing}
                onClick={() => doReindex(true)}>强制重建全部 / Rebuild all</Button>
            </div>
          )}

          {/* 检索测试台 / Retrieval test console */}
          <div className="rounded-xl p-4 border" style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
            <div className="text-sm font-medium mb-3">检索测试 / Retrieval Test</div>
            <div className="flex gap-2">
              <Input value={query} onChange={v => setQuery(v as string)} placeholder="输入问题测试知识召回，例如：GMV"
                onEnter={doSearch} />
              <Button theme="primary" icon={<SearchIcon />} loading={busy} onClick={doSearch}>检索</Button>
            </div>
            {hits && (
              <div className="mt-3 space-y-2">
                {hits.length === 0 && <p className="text-xs" style={{ color: 'var(--td-text-color-placeholder)' }}>无匹配结果</p>}
                {hits.map((h, i) => (
                  <div key={h.id} className="p-2 rounded-lg text-xs" style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
                    <div className="flex items-center gap-2 mb-1">
                      <Tag size="small" theme="primary" variant="light">#{i + 1}</Tag>
                      <Tag size="small" variant="outline">{KIND_LABELS[h.kind] || h.kind}</Tag>
                      <span className="font-medium">{h.title}</span>
                      <span className="ml-auto" style={{ color: 'var(--td-text-color-placeholder)' }}>
                        score={h.score.toFixed(3)}
                      </span>
                    </div>
                    <p style={{ color: 'var(--td-text-color-secondary)' }}>{h.content}</p>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* 知识条目列表 / Entry list */}
          <div>
            <div className="text-sm font-medium mb-3">知识条目 / Knowledge Entries</div>
            {loading ? <div className="flex justify-center py-10"><Loading /></div> : (
              <div className="space-y-2">
                {entries.length === 0 && (
                  <p className="text-sm text-center py-10" style={{ color: 'var(--td-text-color-placeholder)' }}>
                    暂无知识，点击「录入知识」添加指标口径或业务术语
                  </p>
                )}
                {entries.map(e => (
                  <div key={e.id} className="p-4 rounded-xl border group"
                    style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
                    <div className="flex items-center gap-2 mb-2">
                      <Tag size="small" theme={e.kind === 'metric' ? 'primary' : 'default'} variant="light">
                        {KIND_LABELS[e.kind] || e.kind}
                      </Tag>
                      <span className="text-sm font-medium">{e.title}</span>
                      <Tag size="small" variant="outline">{e.sourceType}</Tag>
                      {e.formula && <code className="text-xs px-2 py-0.5 rounded" style={{ backgroundColor: 'var(--td-bg-color-component)' }}>{e.formula}</code>}
                      <Button className="ml-auto opacity-0 group-hover:opacity-100" size="small" variant="text"
                        icon={<BrowseIcon />} onClick={() => openDetail(e.id)}>详情</Button>
                      <Button className="opacity-0 group-hover:opacity-100" size="small" variant="text"
                        icon={<DeleteIcon />} onClick={() => remove(e.id)} />
                    </div>
                    <p className="text-xs leading-relaxed" style={{ color: 'var(--td-text-color-secondary)' }}>{e.content}</p>
                    {e.tags && e.tags.length > 0 && (
                      <div className="flex gap-1 mt-2">
                        {e.tags.map(t => <Tag key={t} size="small" variant="light">{t}</Tag>)}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
      }

      {/* 录入对话框 / Entry dialog */}
      <Dialog header="录入知识 / Add Knowledge" visible={addOpen} onClose={() => setAddOpen(false)}
        onConfirm={submit} confirmBtn={{ loading: busy, content: '保存' }}>
        <div className="space-y-3">
          <div>
            <label className="block text-xs mb-1">类型 / Kind</label>
            <Select value={form.kind} onChange={v => setForm({ ...form, kind: v as string })} options={KIND_OPTIONS} />
          </div>
          <div>
            <label className="block text-xs mb-1">名称 / Title</label>
            <Input value={form.title} onChange={v => setForm({ ...form, title: v as string })} placeholder="例如：GMV" />
          </div>
          <div>
            <label className="block text-xs mb-1">内容 / Content</label>
            <Textarea value={form.content} onChange={v => setForm({ ...form, content: v as string })}
              placeholder="指标定义、业务含义、使用场景..." autosize={{ minRows: 3, maxRows: 8 }} />
          </div>
          <div>
            <label className="block text-xs mb-1">公式（可选）/ Formula (optional)</label>
            <Input value={form.formula} onChange={v => setForm({ ...form, formula: v as string })}
              placeholder="例如：sum(销售额)-sum(成本)" />
          </div>
          <div>
            <label className="block text-xs mb-1">关联字段（逗号分隔）/ Fields (comma separated)</label>
            <Input value={(form.fields || []).join(',')}
              onChange={v => setForm({ ...form, fields: (v as string).split(',').map(s => s.trim()).filter(Boolean) })}
              placeholder="销售额,成本" />
          </div>
        </div>
      </Dialog>

      {/* 详情抽屉 / Detail dialog: metadata + vector status + lineage */}
      <Dialog header="知识详情 / Knowledge Detail" visible={detailOpen}
        onClose={() => { setDetailOpen(false); setDetail(null); }} footer={false} width={720}>
        {detailLoading ? <div className="flex justify-center py-16"><Loading /></div> : detail && (
          <div className="space-y-4 max-h-[70vh] overflow-y-auto pr-1">
            {/* 基本信息 / Basic info */}
            <section>
              <h4 className="text-xs font-semibold mb-2" style={{ color: 'var(--td-brand-color)' }}>基本信息 / Basic Info</h4>
              <div className="grid grid-cols-2 gap-2 text-xs p-3 rounded-lg" style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
                <Field label="标题 / Title" value={detail.entry?.title} />
                <Field label="类型 / Kind" value={detail.entry?.kind} />
                <Field label="来源 / Source" value={detail.sourceLabel} />
                <Field label="数据集 / Dataset" value={detail.datasetName} />
                <Field label="状态 / Status" value={detail.entry?.status} />
                <Field label="块序号 / Chunk" value={String(detail.entry?.chunkIndex ?? 0)} />
                <Field label="创建时间 / Created" value={detail.entry?.createdAt} />
                <Field label="哈希 / Hash" value={detail.entry?.textHash?.slice(0, 16)} />
              </div>
            </section>

            {/* 正文 / Content */}
            <section>
              <h4 className="text-xs font-semibold mb-2" style={{ color: 'var(--td-brand-color)' }}>知识内容 / Content</h4>
              <pre className="text-xs p-3 rounded-lg whitespace-pre-wrap font-sans"
                style={{ backgroundColor: 'var(--td-bg-color-component)', color: 'var(--td-text-color-primary)' }}>
                {detail.entry?.content}
              </pre>
              {detail.entry?.formula && (
                <div className="mt-2 text-xs">
                  <span style={{ color: 'var(--td-text-color-secondary)' }}>公式 / Formula: </span>
                  <code className="px-2 py-0.5 rounded" style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
                    {detail.entry.formula}
                  </code>
                </div>
              )}
              {(detail.entry?.fields?.length ?? 0) > 0 && (
                <div className="mt-2 flex gap-1 flex-wrap">
                  {detail.entry?.fields?.map(f => <Tag key={f} size="small" variant="outline">{f}</Tag>)}
                </div>
              )}
            </section>

            {/* 向量状态 / Vector status */}
            <section>
              <h4 className="text-xs font-semibold mb-2" style={{ color: 'var(--td-brand-color)' }}>向量状态 / Vector Status</h4>
              <div className="flex items-center gap-3 text-xs p-3 rounded-lg" style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
                {detail.vector?.present ? (
                  <>
                    <Tag theme="success" variant="light">已向量化 / Embedded</Tag>
                    <span>维度 / Dim: {detail.vector.dimension}</span>
                    <span>内容长度 / Length: {detail.vector.contentLength}</span>
                  </>
                ) : (
                  <Tag theme="warning" variant="light">向量缺失 / Vector missing（未配置 Embedding API）</Tag>
                )}
              </div>
            </section>

            {/* 处理链路 / Lineage */}
            <section>
              <h4 className="text-xs font-semibold mb-2" style={{ color: 'var(--td-brand-color)' }}>处理链路 / Processing Lineage</h4>
              <div className="space-y-1.5">
                {(detail.lineage || []).map((s, i) => (
                  <div key={i} className="flex gap-2 text-xs items-start p-2 rounded-lg"
                    style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
                    <Tag size="small" theme="primary" variant="light">{s.step}</Tag>
                    <span className="font-medium whitespace-nowrap">{s.stage}</span>
                    <span style={{ color: 'var(--td-text-color-secondary)' }}>{s.detail}</span>
                  </div>
                ))}
              </div>
            </section>

            {/* 存储位置 / Storage */}
            <section>
              <h4 className="text-xs font-semibold mb-2" style={{ color: 'var(--td-brand-color)' }}>存储位置 / Storage</h4>
              <div className="text-xs p-3 rounded-lg space-y-1" style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
                {Object.entries(detail.storage || {}).map(([k, v]) => (
                  <div key={k}><span style={{ color: 'var(--td-text-color-secondary)' }}>{k}: </span><code>{String(v)}</code></div>
                ))}
              </div>
            </section>
          </div>
        )}
      </Dialog>
    </div>
  );
}

/** 详情字段展示 / Detail field renderer. */
function Field({ label, value }: { label: string; value?: string }) {
  return (
    <div className="truncate" title={value}>
      <span style={{ color: 'var(--td-text-color-secondary)' }}>{label}: </span>
      <span style={{ color: 'var(--td-text-color-primary)' }}>{value || '-'}</span>
    </div>
  );
}
