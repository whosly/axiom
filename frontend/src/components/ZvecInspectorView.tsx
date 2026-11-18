import { useEffect, useState } from 'react';
import { Button, Tag, Dialog, Loading, Select } from 'tdesign-react';
import { RefreshIcon, BrowseIcon } from 'tdesign-icons-react';
import { api } from '../api';

/**
 * Zvec 数据查看视图 / Zvec data inspection view.
 * 展示两个 collection 的统计、文档列表和单文档详情（含向量维度）。
 */
export function ZvecInspectorView() {
  const [collections, setCollections] = useState<any[]>([]);
  const [active, setActive] = useState('knowledge_base');
  const [docs, setDocs] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<any>(null);

  const loadCollections = async () => {
    const r: any = await api.zvecCollections();
    setCollections(r.collections || []);
  };

  const loadDocs = async (name: string) => {
    setLoading(true);
    try {
      const r: any = await api.zvecDocs(name, 100);
      setDocs(r.docs || []);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  useEffect(() => {
    loadCollections().then(() => loadDocs(active));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const switchCollection = (name: string) => { setActive(name); loadDocs(name); };

  const openDoc = async (id: string) => {
    try { setDetail((await api.zvecDoc(active, id)).doc); }
    catch (e) { alert('加载失败: ' + (e as Error).message); }
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
        {loading ? <div className="flex justify-center py-10"><Loading /></div> : (
          <div className="max-w-5xl mx-auto space-y-2">
            {docs.length === 0 && (
              <p className="text-sm text-center py-10" style={{ color: 'var(--td-text-color-placeholder)' }}>
                该 collection 暂无文档（向量写入需要配置 Embedding API）
              </p>
            )}
            {docs.map((d: any) => (
              <div key={d.id} className="p-3 rounded-lg border flex items-center gap-3"
                style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
                <Tag size="small" variant="outline">{String(d.kind || '-')}</Tag>
                <span className="text-sm font-medium truncate flex-1">{String(d.title || d.id)}</span>
                <span className="text-xs" style={{ color: 'var(--td-text-color-placeholder)' }}>
                  {String(d.embedding || '')}
                </span>
                <Button size="small" variant="text" icon={<BrowseIcon />} onClick={() => openDoc(d.id)}>详情</Button>
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
