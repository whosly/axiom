import { useEffect, useState, useMemo, useRef } from 'react';
import ReactECharts from 'echarts-for-react';
import { Button, Tag, Loading, Select } from 'tdesign-react';
import { RefreshIcon } from 'tdesign-icons-react';
import { api } from '../api';
import { notifyError } from '../notify';
import { GraphPayload, GraphNode, nodeId, nodeLabel, nodeType, edgeSource, edgeTarget, edgeRelation } from '../types';

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

/** 分类顺序与 legend/节点 category 共用 / Shared category order for legend & node.category. */
const CATEGORIES = Object.keys(NODE_COLORS);

/** ECharts tooltip 回调参数的最小类型 / Minimal shape of the ECharts tooltip callback param. */
interface TooltipParam {
  dataType?: string;
  data: { name?: string; value?: string; source?: string; target?: string };
}

export function KnowledgeGraphView() {
  // 图数据只存节点与边；统计由当前显示的数据实时计算，聚焦子图后仍准确
  // Store only nodes/edges; stats are derived from displayed data so they stay correct after focusing.
  const [graph, setGraph] = useState<GraphPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<string>('');
  // 加载失败提示条：失败不能吞成"暂无数据"空态，误导用户去点重建
  // / Load-failure banner: failures must surface, not masquerade as an empty graph.
  const [loadError, setLoadError] = useState<string>('');
  // 请求序号 + 卸载守卫：过期响应（被更新的请求超越）与卸载后的 in-flight 响应
  // 都不回写 state——只判卸载会让慢的旧响应覆盖新数据。
  // / Request sequence + unmount guard: stale responses (superseded by newer ones)
  // and post-unmount responses never write back.
  const loadSeq = useRef(0);
  const alive = () => loadSeq.current >= 0;

  const load = async () => {
    const seq = ++loadSeq.current;
    setLoading(true);
    setLoadError('');
    try {
      const g = await api.getGraph();
      if (!alive() || seq !== loadSeq.current) return;
      setGraph({ nodes: g.nodes || [], edges: g.edges || [] });
    } catch (e) {
      if (!alive() || seq !== loadSeq.current) return;
      setLoadError('图谱加载失败: ' + (e as Error).message);
    } finally {
      // 只有仍是最新请求才关闭 loading；被超越的旧请求不得提前收尾。
      // / Only the latest request may clear loading.
      if (alive() && seq === loadSeq.current) setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // 卸载后 alive() 恒为 false / alive() stays false once unmounted.
    return () => { loadSeq.current = -1; };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const rebuild = async () => {
    setLoading(true);
    setLoadError('');
    try { await api.rebuildGraph(); await load(); }
    catch (e) { notifyError('重建失败: ' + (e as Error).message); }
    finally { if (loadSeq.current >= 0) setLoading(false); }
  };

  const focusNeighbors = async (id: string) => {
    if (!id) { load(); return; }
    const seq = ++loadSeq.current;
    setLoading(true);
    setLoadError('');
    try {
      const r = await api.graphNeighbors(id, 2);
      if (!alive() || seq !== loadSeq.current) return;
      setGraph({ nodes: r.nodes || [], edges: r.edges || [] });
    } catch (e) {
      if (!alive() || seq !== loadSeq.current) return;
      setLoadError('邻居查询失败: ' + (e as Error).message);
    } finally {
      if (alive() && seq === loadSeq.current) setLoading(false);
    }
  };

  const option = useMemo(() => {
    const nodes = (graph?.nodes || []).map((n: GraphNode) => {
      const type = nodeType(n);
      return {
        id: nodeId(n),
        name: nodeLabel(n),
        value: type,
        symbolSize: type === 'knowledge' ? 24 : 18,
        // 节点必须关联 category：否则 legend 点击切换、分类着色全部失效。
        // / Nodes must reference a category index, or legend toggling and
        // category-based coloring do nothing.
        category: Math.max(0, CATEGORIES.indexOf(type)),
      };
    });
    const links = (graph?.edges || []).map((e) => ({
      source: edgeSource(e),
      target: edgeTarget(e),
      value: edgeRelation(e),
    }));
    return {
      tooltip: {
        formatter: (p: TooltipParam) => p.dataType === 'edge'
          ? `${p.data.source} --${p.data.value}--> ${p.data.target}`
          : `${p.data.name} (${p.data.value})`,
      },
      legend: [{
        data: CATEGORIES,
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
        // 分类颜色统一由 categories 管理，节点不再手写 itemStyle。
        // / Colors come from the categories; nodes no longer hardcode itemStyle.
        categories: CATEGORIES.map(name => ({ name, itemStyle: { color: NODE_COLORS[name] } })),
        label: { show: true, position: 'right', fontSize: 10, color: '#888' },
        edgeLabel: { show: true, formatter: (p: TooltipParam) => p.data.value, fontSize: 9, color: '#aaa' },
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
          {graph && (
            <>
              <Tag size="small" variant="light">节点 {graph.nodes.length}</Tag>
              <Tag size="small" variant="light">关系 {graph.edges.length}</Tag>
            </>
          )}
        </div>
        <div className="flex gap-2">
          <Select size="small" value={selected || undefined} placeholder="聚焦节点（查2跳邻居）" clearable
            style={{ width: 220 }}
            onChange={v => { setSelected(v as string || ''); focusNeighbors(v as string || ''); }}
            options={(graph?.nodes || []).map((n: GraphNode) => ({
              label: nodeLabel(n), value: nodeId(n),
            }))} />
          <Button size="small" variant="outline" icon={<RefreshIcon />} onClick={load}>刷新</Button>
          <Button size="small" theme="primary" onClick={rebuild}>重建图谱</Button>
        </div>
      </header>
      <div className="flex-1 min-h-0 p-2">
        {loadError && (
          <div className="mx-2 mt-2 px-3 py-2 rounded text-sm"
            style={{ background: 'var(--td-error-color-1)', color: 'var(--td-error-color-6)' }}>
            {loadError}
          </div>
        )}
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
