import { useCallback, useEffect, useState } from 'react';
import { Button, Tag, Loading, Empty } from 'tdesign-react';
import { DeleteIcon, RefreshIcon, BrowseIcon } from 'tdesign-icons-react';
import { api } from '../api';
import { confirmDialog, notifyError, notifyInfo, notifySuccess } from '../notify';

/** 知识来源批次管理 / Knowledge source batch management. */

type Source = {
  id: string; name: string; type: string; description?: string;
  entryCount: number; status: string; createdAt?: string; updatedAt?: string;
};

/** 按来源查看与级联删除知识 / View and cascade-delete knowledge by source. */
export function KnowledgeSourcesView() {
  const [sources, setSources] = useState<Source[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try { setSources(await api.listSources()); }
    catch (e) { notifyError('加载来源失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  /** 删除来源及其全部条目 / Delete a source and all its entries. */
  const remove = async (s: Source) => {
    if (!(await confirmDialog('删除来源', `确定删除「${s.name}」及其 ${s.entryCount} 条知识吗？`))) return;
    setLoading(true);
    try {
      const r = await api.deleteSource(s.id);
      notifySuccess(`已删除 / Deleted: ${r.deleted} 条`);
      await load();
    } catch (e) { notifyError('删除失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  /** 查看该来源的条目 / View entries for a source. */
  const viewEntries = (s: Source) => {
    // 通过检索页按来源过滤：跳转到检索并提示 / Use search with a source hint.
    notifyInfo(`来源 ID / Source ID: ${s.id}\n可在「知识条目」中按标题检索该批次内容。`);
  };

  return (
    <div className="h-full flex flex-col">
      <header className="h-12 flex items-center justify-between px-5 border-b flex-shrink-0"
        style={{ borderColor: 'var(--td-component-stroke)' }}>
        <span className="text-sm font-medium">知识来源 / Sources</span>
        <Button size="small" variant="outline" icon={<RefreshIcon />} onClick={load}>刷新</Button>
      </header>
      <div className="flex-1 overflow-y-auto p-5">
        <div className="max-w-3xl mx-auto">
          {loading ? <div className="flex justify-center py-16"><Loading /></div> :
            sources.length === 0 ? <Empty description="暂无来源数据" /> : (
              <div className="space-y-2">
                {sources.map(s => (
                  <div key={s.id} className="p-4 rounded-xl border"
                    style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
                    <div className="flex items-center gap-2 flex-wrap">
                      <BrowseIcon size={16} />
                      <span className="text-sm font-medium">{s.name}</span>
                      <Tag size="small" variant="light">{s.type}</Tag>
                      <Tag size="small" theme={s.status === 'active' ? 'success' : 'default'} variant="light">{s.status}</Tag>
                      <Tag size="small" variant="outline">{s.entryCount} 条知识</Tag>
                      <div className="ml-auto flex gap-1">
                        <Button size="small" variant="text" onClick={() => viewEntries(s)}>查看</Button>
                        <Button size="small" variant="text" icon={<DeleteIcon />} onClick={() => remove(s)} />
                      </div>
                    </div>
                    {s.description && <p className="text-xs mt-2" style={{ color: 'var(--td-text-color-secondary)' }}>{s.description}</p>}
                    <div className="text-xs mt-1" style={{ color: 'var(--td-text-color-placeholder)' }}>
                      ID: {s.id} · 更新于 {s.updatedAt || s.createdAt || '-'}
                    </div>
                  </div>
                ))}
              </div>
            )}
        </div>
      </div>
    </div>
  );
}
