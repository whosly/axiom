import { useEffect, useMemo, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import { Button, Tag, Loading, Select } from 'tdesign-react';
import { RefreshIcon } from 'tdesign-icons-react';
import { api } from '../api';

/**
 * 知识图谱视图 / Knowledge graph view.
 * 用 ECharts graph 展示实体关系，支持重建和按节点查邻居。
 */
const NODE_COLORS: Record<string, string> = {
  knowledge: '#0052d9',
  dataset: '#00a870',
  field: '#ed7b2f',
  tag: '#8e56ff',
  intent: '#e34d59',
};

export function KnowledgeGraphView() {
  const [graph, setGraph] = useState<{ nodes: any[]; edges: any[]; stats: any } | null>(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string>('');

  const load = async () => {
    setLoading(true);
    try {
      const g: any = await api.getGraph();
      setGraph({ nodes: g.nodes || [], edges: g.edges || [], stats: g.stats });
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const rebuild = async () => {
    setLoading(true);
    try { await api.rebuildGraph(); await load(); }
    catch (e) { alert('重建失败: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  const focusNeighbors = async (nodeId: string) => {
    if (!nodeId) { load(); return; }
    setLoading(true);
    try {
      const r: any = await api.graphNeighbors(nodeId, 2);
      setGraph({ nodes: r.nodes || [], edges: r.edges || [], stats: graph?.stats });
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  const option = useMemo(() => {
    const nodes = (graph?.nodes || []).map((n: any) => {
      const type = String(n.TYPE ?? n.type ?? 'knowledge');
      return {
        id: String(n.ID ?? n.id),
        name: String(n.LABEL ?? n.label ?? n.ID ?? n.id),
        value: type,
        symbolSize: type === 'knowledge' ? 24 : 18,
        itemStyle: { color: NODE_COLORS[type] || '#999' },
      };
    });
    const links = (graph?.edges || []).map((e: any) => ({
      source: String(e.SOURCE ?? e.source),
      target: String(e.TARGET ?? e.target),
      value: String(e.RELATION ?? e.relation),
    }));
    return {
      tooltip: {
        formatter: (p: any) => p.dataType === 'edge'
          ? `${p.data.source} --${p.data.value}--> ${p.data.target}`
          : `${p.data.name} (${p.data.value})`,
      },
      legend: [{
        data: Object.keys(NODE_COLORS),
        textStyle: { color: '#888' },
        bottom: 0,
      }],
      series: [{
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        data: nodes,
        links,
        categories: Object.keys(NODE_COLORS).map(name => ({ name })),
        label: { show: true, position: 'right', fontSize: 10, color: '#888' },
        edgeLabel: { show: true, formatter: (p: any) => p.data.value, fontSize: 9, color: '#aaa' },
        force: { repulsion: 260, edgeLength: 90 },
        lineStyle: { color: 'source', curveness: 0.1 },
      }],
    };
  }, [graph]);

  return (
    <div className="h-full flex flex-col">
      <header className="h-14 flex items-center justify-between px-5 border-b flex-shrink-0"
        style={{ borderColor: 'var(--td-component-stroke)' }}>
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">知识图谱 / Knowledge Graph</span>
          {graph?.stats && (
            <>
              <Tag size="small" variant="light">节点 {String((graph.stats as any).NODES ?? (graph.stats as any).nodes ?? 0)}</Tag>
              <Tag size="small" variant="light">关系 {String((graph.stats as any).EDGES ?? (graph.stats as any).edges ?? 0)}</Tag>
            </>
          )}
        </div>
        <div className="flex gap-2">
          <Select size="small" value={selected || undefined} placeholder="聚焦节点（查2跳邻居）" clearable
            style={{ width: 220 }}
            onChange={v => { setSelected(v as string || ''); focusNeighbors(v as string || ''); }}
            options={(graph?.nodes || []).map((n: any) => ({
              label: String(n.LABEL ?? n.label ?? ''), value: String(n.ID ?? n.id),
            }))} />
          <Button size="small" variant="outline" icon={<RefreshIcon />} onClick={load}>刷新</Button>
          <Button size="small" theme="primary" onClick={rebuild}>重建图谱</Button>
        </div>
      </header>
      <div className="flex-1 min-h-0 p-2">
        {loading ? <div className="flex justify-center items-center h-full"><Loading /></div> : (
          graph && graph.nodes.length > 0
            ? <ReactECharts option={option} style={{ height: '100%', width: '100%' }} notMerge />
            : <div className="flex items-center justify-center h-full text-sm" style={{ color: 'var(--td-text-color-placeholder)' }}>
                暂无图谱数据，点击「重建图谱」从知识条目生成
              </div>
        )}
      </div>
    </div>
  );
}
