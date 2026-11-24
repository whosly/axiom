import { useCallback, useEffect, useState } from 'react';
import { Button, InputNumber, Select, Tag, Loading, Empty } from 'tdesign-react';
import { SearchIcon } from 'tdesign-icons-react';
import { api } from '../api';
import { notifyError } from '../notify';

/** 图谱推理视图 / Graph reasoning view: communities, shortest path and related entries. */

type Community = { size: number; members: Array<{ id: string; label: string }>; typeDistribution: Record<string, number> };
type PathResult = { path: Array<{ id: string; label: string; type: string }>; length: number;
  reachable: boolean; message?: string };
type Related = { id: string; label: string; sharedRelations: number };

export function GraphReasoningView() {
  const [tab, setTab] = useState<'communities' | 'path' | 'related'>('communities');
  const [loading, setLoading] = useState(false);
  const [communities, setCommunities] = useState<Community[]>([]);
  const [iterations, setIterations] = useState(0);
  // 大图采样提示 / Large-graph sampling metadata
  const [sampling, setSampling] = useState<{ sampled: boolean; total: number; analyzed: number } | null>(null);
  const [nodeOptions, setNodeOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [path, setPath] = useState<PathResult | null>(null);
  const [entryId, setEntryId] = useState('');
  const [limit, setLimit] = useState(10);
  const [related, setRelated] = useState<Related[]>([]);

  /** 加载图谱节点供下拉选择 / Load graph nodes for dropdowns. */
  useEffect(() => {
    api.getGraph().then((g: any) => {
      setNodeOptions((g.nodes || []).map((n: any) => ({
        label: String(n.LABEL ?? n.label ?? n.ID ?? n.id),
        value: String(n.ID ?? n.id),
      })));
      const kinds = (g.nodes || []).filter((n: any) => String(n.TYPE ?? n.type) === 'knowledge');
      if (kinds.length && !entryId) setEntryId(String(kinds[0].ID ?? kinds[0].id));
    }).catch(e => console.error(e));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** 社区发现 / Detect communities. */
  const loadCommunities = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.graphCommunities(20);
      setCommunities(r.communities || []);
      setIterations(r.iterations || 0);
      setSampling({ sampled: !!r.sampled, total: r.totalNodes || 0, analyzed: r.analyzedNodes || 0 });
    } catch (e) { notifyError('社区发现失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { if (tab === 'communities') loadCommunities(); }, [tab, loadCommunities]);

  /** 最短路径 / Shortest path. */
  const findPath = async () => {
    if (!from || !to) return;
    setLoading(true);
    try { setPath(await api.graphPath(from, to)); }
    catch (e) { notifyError('路径查询失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  /** 关联分析 / Related entries. */
  const findRelated = async () => {
    if (!entryId) return;
    setLoading(true);
    try { setRelated((await api.graphRelated(entryId, limit)).related || []); }
    catch (e) { notifyError('关联分析失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  return (
    <div className="h-full flex flex-col">
      <header className="h-12 flex items-center gap-2 px-5 border-b flex-shrink-0"
        style={{ borderColor: 'var(--td-component-stroke)' }}>
        <span className="text-sm font-medium">图谱推理 / Reasoning</span>
        <div className="flex gap-1 ml-4">
          <Button size="small" variant={tab === 'communities' ? 'base' : 'text'}
            onClick={() => setTab('communities')}>社区发现</Button>
          <Button size="small" variant={tab === 'path' ? 'base' : 'text'}
            onClick={() => setTab('path')}>最短路径</Button>
          <Button size="small" variant={tab === 'related' ? 'base' : 'text'}
            onClick={() => setTab('related')}>关联分析</Button>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto p-5">
        <div className="max-w-3xl mx-auto">
          {loading && <div className="flex justify-center py-16"><Loading /></div>}

          {!loading && tab === 'communities' && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <p className="text-xs" style={{ color: 'var(--td-text-color-secondary)' }}>
                  标签传播算法 / Label propagation，共 {communities.length} 个社区，{iterations} 轮迭代
                  {sampling?.sampled && (
                    <span style={{ color: 'var(--td-warning-color)' }}>
                      {' '}· 大图采样 / Sampled: {sampling.analyzed}/{sampling.total} 节点
                    </span>
                  )}
                </p>
                <Button size="small" variant="outline" onClick={loadCommunities}>重新计算</Button>
              </div>
              {communities.length === 0 ? <Empty description="暂无社区数据，请先重建图谱" /> :
                communities.map((c, i) => (
                  <div key={i} className="p-3 rounded-xl border"
                    style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
                    <div className="flex items-center gap-2 mb-2">
                      <Tag theme="primary" variant="light">社区 {i + 1}</Tag>
                      <span className="text-xs">{c.size} 个节点</span>
                    </div>
                    <div className="flex flex-wrap gap-1">
                      {c.members.slice(0, 20).map(m => <Tag key={m.id} size="small" variant="outline">{m.label}</Tag>)}
                      {c.members.length > 20 && <Tag size="small">+{c.members.length - 20}</Tag>}
                    </div>
                  </div>
                ))}
            </div>
          )}

          {!loading && tab === 'path' && (
            <div className="space-y-4">
              <div className="flex gap-2 items-center flex-wrap">
                <Select value={from || undefined} onChange={v => setFrom(v as string)} placeholder="起点节点"
                  style={{ width: 240 }} options={nodeOptions} filterable />
                <span>→</span>
                <Select value={to || undefined} onChange={v => setTo(v as string)} placeholder="终点节点"
                  style={{ width: 240 }} options={nodeOptions} filterable />
                <Button theme="primary" icon={<SearchIcon />} onClick={findPath} disabled={!from || !to}>查询路径</Button>
              </div>
              {path && (
                <div className="p-4 rounded-xl border"
                  style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
                  {path.reachable ? (
                    <>
                      <div className="text-xs mb-3" style={{ color: 'var(--td-text-color-secondary)' }}>
                        最短路径长度 / Length: {path.length}
                      </div>
                      <div className="flex flex-wrap items-center gap-2">
                        {path.path.map((n, i) => (
                          <span key={n.id} className="flex items-center gap-2">
                            <Tag size="small" theme={i === 0 || i === path.path.length - 1 ? 'primary' : 'default'} variant="light">
                              {n.label}
                            </Tag>
                            {i < path.path.length - 1 && <span className="text-xs">→</span>}
                          </span>
                        ))}
                      </div>
                    </>
                  ) : (
                    <p className="text-sm" style={{ color: 'var(--td-text-color-placeholder)' }}>
                      {path.message || '两节点之间不可达 / Not reachable'}
                    </p>
                  )}
                </div>
              )}
            </div>
          )}

          {!loading && tab === 'related' && (
            <div className="space-y-4">
              <div className="flex gap-2 items-center flex-wrap">
                <Select value={entryId || undefined} onChange={v => setEntryId(v as string)} placeholder="选择知识条目"
                  style={{ width: 280 }} options={nodeOptions.filter(n => n.value.startsWith('knowledge:'))} filterable />
                <InputNumber value={limit} onChange={v => setLimit(Number(v) || 10)} min={1} max={50}
                  style={{ width: 100 }} />
                <Button theme="primary" icon={<SearchIcon />} onClick={findRelated} disabled={!entryId}>分析关联</Button>
              </div>
              {related.length > 0 && (
                <div className="space-y-2">
                  {related.map(r => (
                    <div key={r.id} className="flex items-center justify-between p-3 rounded-xl border"
                      style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
                      <span className="text-sm">{r.label}</span>
                      <Tag size="small" theme="primary" variant="light">{r.sharedRelations} 个共享关系</Tag>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
