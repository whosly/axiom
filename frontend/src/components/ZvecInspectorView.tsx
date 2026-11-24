import { useEffect, useRef, useState } from 'react';
import { Button, Tag, Dialog, Loading, Select } from 'tdesign-react';
import { RefreshIcon, BrowseIcon } from 'tdesign-icons-react';
import { api } from '../api';
import { notifyError } from '../notify';
import { ZvecCollection, ZvecDoc } from '../types';

/**
 * Zvec 数据查看视图 / Zvec data inspection view.
 * 展示两个 collection 的统计、文档列表和单文档详情（含向量维度）。
 */
export function ZvecInspectorView() {
  const [collections, setCollections] = useState<ZvecCollection[]>([]);
  const [active, setActive] = useState('knowledge_base_v2');
  const [docs, setDocs] = useState<ZvecDoc[]>([]);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<Record<string, unknown> | null>(null);

  const [loadError, setLoadError] = useState<string | null>(null);

  /** 返回本次应加载的集合名（失败返回空串）/ Returns the collection name to load ('' on failure). */
  const loadCollections = async (): Promise<string> => {
    try {
      const r = await api.zvecCollections();
      setCollections(r.collections || []);
      setLoadError(null);
      const list = r.collections || [];
      // 初始 collection 从后端列表取第一个，而不是硬编码名称：
      // 配置改名后硬编码值会请求一个不存在的集合，且 Select 显示空白。
      // / Pick the first collection from the backend list instead of a hardcoded name:
      // a renamed config would query a missing collection and leave the Select blank.
      const chosen = list.some(c => c.name === active) ? active : (list[0]?.name || '');
      setActive(chosen);
      return chosen;
    } catch (e) {
      console.error(e);
      setLoadError('集合列表加载失败 / Failed to load collections: ' + (e as Error).message);
      return '';
    }
  };

  // 请求序号守卫：快速切换 collection 时，慢的旧响应会被丢弃，
  // 避免把 A 集合的文档显示在 B 集合下。/ Sequence guard: stale responses from a
  // previously selected collection are dropped instead of overwriting the current view.
  const loadSeq = useRef(0);

  const loadDocs = async (name: string) => {
    const seq = ++loadSeq.current;
    setLoading(true);
    try {
      const r = await api.zvecDocs(name, 100);
      if (seq !== loadSeq.current) return;
      setDocs(r.docs || []);
    } catch (e) {
      console.error(e);
      // 过期请求的错误不再展示，避免旧集合的错误信息落到当前集合视图。
      // / Stale errors are dropped so an old collection's failure never
      // surfaces under the currently selected collection.
      if (seq === loadSeq.current) {
        setLoadError('文档列表加载失败 / Failed to load documents: ' + (e as Error).message);
      }
    }
    finally { if (seq === loadSeq.current) setLoading(false); }
  };

  useEffect(() => {
    // 无论集合列表是否失败都要加载文档并解除 loading，否则界面永久转圈。
    // 用列表解析出的集合名请求文档，而不是闭包里的硬编码初始值。
    // Always load docs and clear loading even if collection listing fails; use the
    // resolved collection name rather than the stale closure value.
    loadCollections().then(name => loadDocs(name));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const switchCollection = (name: string) => { setActive(name); loadDocs(name); };

  const openDoc = async (id: string) => {
    try { setDetail((await api.zvecDoc(active, id)).doc); }
    catch (e) { notifyError('加载失败: ' + (e as Error).message); }
  };

  return (
    <div className="h-full flex flex-col">
      <header className="h-14 flex items-center justify-between px-5 border-b flex-shrink-0"
        style={{ borderColor: 'var(--td-component-stroke)' }}>
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">Zvec 数据查看 / Vector Store Inspector</span>
        </div>
        <div className="flex gap-2">
          <Select size="small" value={active} onChange={v => switchCollection(v as string)}
            options={collections.map(c => ({ label: `${c.name} (${c.docCount ?? 0})`, value: c.name }))}
            style={{ width: 220 }} />
          <Button size="small" variant="outline" icon={<RefreshIcon />}
            onClick={() => { loadCollections(); loadDocs(active); }}>刷新</Button>
        </div>
      </header>

      {/* 集合统计 / Collection stats */}
      <div className="px-5 py-3 flex gap-3 flex-wrap border-b" style={{ borderColor: 'var(--td-component-stroke)' }}>
        {collections.map(c => (
          <div key={c.name} className="px-3 py-2 rounded-lg text-xs" style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
            <b>{c.name}</b> · 文档 {String(c.docCount ?? 0)} · 索引 {String(c.indexCount ?? 0)}
            {!c.ready && <Tag size="small" theme="warning" variant="light">不可用</Tag>}
          </div>
        ))}
      </div>

      <div className="flex-1 overflow-y-auto p-5">
        {loadError && (
          <div className="mb-3 px-3 py-2 rounded text-sm"
            style={{ background: 'var(--td-error-color-1)', color: 'var(--td-error-color-6)' }}>
            {loadError}
          </div>
        )}
        {loading ? <div className="flex justify-center py-10"><Loading /></div> : (
          <div className="max-w-5xl mx-auto space-y-2">
            {docs.length === 0 && (
              <p className="text-sm text-center py-10" style={{ color: 'var(--td-text-color-placeholder)' }}>
                该 collection 暂无文档（向量写入需要配置 Embedding API）
              </p>
            )}
            {docs.map(d => (
              <div key={d.id} className="p-3 rounded-lg border flex items-center gap-3"
                style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
                <Tag size="small" variant="outline">{String(d.kind || '-')}</Tag>
                <span className="text-sm font-medium truncate flex-1">{String(d.title || d.id)}</span>
                <span className="text-xs" style={{ color: 'var(--td-text-color-placeholder)' }}>
                  {String(d.embedding || '')}
                </span>
                <Button size="small" variant="text" icon={<BrowseIcon />} onClick={() => openDoc(d.id!)}>详情</Button>
              </div>
            ))}
          </div>
        )}
      </div>

      <Dialog header="Zvec 文档详情 / Document Detail" visible={detail !== null}
        onClose={() => setDetail(null)} footer={false} width={700}>
        {detail && (
          <pre className="text-xs p-3 rounded-lg whitespace-pre-wrap overflow-auto max-h-[60vh]"
            style={{ backgroundColor: 'var(--td-bg-color-component)' }}>
            {JSON.stringify(detail, null, 2)}
          </pre>
        )}
      </Dialog>
    </div>
  );
}
