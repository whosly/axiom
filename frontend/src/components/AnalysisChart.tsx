import { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import { Empty } from 'tdesign-react';
import type { ChartSpec } from '../types';

interface Props {
  chart: ChartSpec;
  dark?: boolean;
}

const PALETTE = ['#0052d9', '#00a870', '#ed7b2f', '#8e56ff', '#e34d59', '#0594fa'];

/** 千分位格式化 / Thousands separator formatting. */
const fmtNum = (v: number): string =>
  Number.isFinite(v) ? v.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : String(v);

export function AnalysisChart({ chart, dark }: Props) {
  const rows = chart.series || [];
  // tolerate both camelCase and all-lowercase field names from different serializers
  const c = chart as unknown as Record<string, unknown>;
  const xField = chart.xField || (c.xfield as string) || '';
  const yFieldsRaw = chart.yFields || (c.yfields as string[]);
  const yFields = yFieldsRaw?.length ? yFieldsRaw : ['value'];

  const option = useMemo(() => {
    const textColor = dark ? '#a3a3a3' : '#737373';
    const lineColor = dark ? '#404040' : '#e5e5e5';

    // ---- pie：单序列本质，只取第一个指标；图例显式列出维度值，避免误导性的多余图例 ----
    if (chart.type === 'pie') {
      const metricName = yFields[0];
      return {
        backgroundColor: 'transparent',
        color: PALETTE,
        title: chart.title ? {
          text: chart.title,
          left: 'center',
          textStyle: { fontSize: 14, fontWeight: 600, color: dark ? '#fafafa' : '#171717' },
        } : undefined,
        tooltip: { trigger: 'item', valueFormatter: (v: number) => fmtNum(v) },
        legend: {
          bottom: 0,
          // 显式列出维度值 / Explicitly list dimension values, not series names.
          data: rows.map(r => String(r[xField] ?? '')),
          textStyle: { color: textColor },
        },
        series: [{
          type: 'pie',
          radius: '60%',
          data: rows.map((r, i) => ({
            name: String(r[xField] ?? ''),
            value: Number(r[metricName] ?? 0),
            itemStyle: { color: PALETTE[i % PALETTE.length] },
          })),
          label: { show: true, formatter: '{b}: {d}%' },
        }],
      };
    }

    // ---- line：rows 带 dimension 时按维度拆成多序列（如"各产品每月趋势"）----
    if (chart.type === 'line') {
      const hasDimension = rows.length > 0 && 'dimension' in rows[0] && xField !== 'dimension';
      const xData = Array.from(new Set(rows.map(r => String(r[xField] ?? ''))));
      if (hasDimension) {
        const dims = Array.from(new Set(rows.map(r => String(r.dimension))));
        const series = dims.map((dim, idx) => ({
          name: dim,
          type: 'line',
          smooth: true,
          symbolSize: 6,
          itemStyle: { color: PALETTE[idx % PALETTE.length] },
          areaStyle: { opacity: 0.08 },
          // 每个维度只取自己的行，按 xData 顺序对齐（缺失期为 null，ECharts 断点显示）
          // / Only this dimension's rows, aligned to xData; missing periods render as gaps.
          data: xData.map(x => {
            const row = rows.find(r => String(r[xField]) === x && String(r.dimension) === dim);
            const v = row ? Number(row[yFields[0]] ?? 0) : null;
            return v !== null && Number.isFinite(v) ? v : null;
          }),
        }));
        return {
          backgroundColor: 'transparent',
          color: PALETTE,
          title: chart.title ? {
            text: chart.title, left: 'center',
            textStyle: { fontSize: 14, fontWeight: 600, color: dark ? '#fafafa' : '#171717' },
          } : undefined,
          tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmtNum(v) },
          legend: { bottom: 0, textStyle: { color: textColor } },
          grid: { left: 12, right: 20, top: chart.title ? 50 : 20, bottom: 40, containLabel: true },
          xAxis: {
            type: 'category', data: xData,
            axisLabel: { color: textColor, rotate: xData.length > 8 ? 30 : 0, fontSize: 11 },
            axisLine: { lineStyle: { color: lineColor } },
          },
          yAxis: {
            type: 'value', axisLabel: { color: textColor, fontSize: 11 },
            splitLine: { lineStyle: { color: lineColor, type: 'dashed' } },
          },
          series,
        };
      }
      // 单序列 line / Single-series line.
      const series = yFields.map((field, idx) => ({
        name: field,
        type: 'line',
        smooth: true,
        itemStyle: { color: PALETTE[idx % PALETTE.length] },
        areaStyle: { opacity: 0.12 },
        data: rows.map(r => {
          const v = Number(r[field] ?? 0);
          return Number.isFinite(v) ? v : null;
        }),
      }));
      return {
        backgroundColor: 'transparent',
        color: PALETTE,
        title: chart.title ? {
          text: chart.title, left: 'center',
          textStyle: { fontSize: 14, fontWeight: 600, color: dark ? '#fafafa' : '#171717' },
        } : undefined,
        tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmtNum(v) },
        legend: yFields.length > 1 ? { bottom: 0, textStyle: { color: textColor } } : undefined,
        grid: { left: 12, right: 20, top: chart.title ? 50 : 20, bottom: yFields.length > 1 ? 40 : 20, containLabel: true },
        xAxis: {
          type: 'category', data: xData,
          axisLabel: { color: textColor, rotate: xData.length > 8 ? 30 : 0, fontSize: 11 },
          axisLine: { lineStyle: { color: lineColor } },
        },
        yAxis: {
          type: 'value', axisLabel: { color: textColor, fontSize: 11 },
          splitLine: { lineStyle: { color: lineColor, type: 'dashed' } },
        },
        series,
      };
    }

    // ---- scatter：异常识别的数据语义是 [类目标签, 数值]（item, value）----
    // 此前把 x 当 value 轴、y 当 category 轴，标签完全不参与定位，图不可读。
    // / Anomaly rows are (item label, value): category x-axis + value y-axis is the only
    // readable mapping. The previous value/category swap rendered points by row index.
    if (chart.type === 'scatter') {
      const xData = rows.map(r => String(r[xField] ?? ''));
      const series = yFields.map((field, idx) => ({
        name: field,
        type: 'scatter',
        symbolSize: 12,
        itemStyle: { color: PALETTE[idx % PALETTE.length] },
        data: rows.map(r => {
          const v = Number(r[field] ?? 0);
          return Number.isFinite(v) ? v : 0;
        }),
      }));
      return {
        backgroundColor: 'transparent',
        color: PALETTE,
        title: chart.title ? {
          text: chart.title, left: 'center',
          textStyle: { fontSize: 14, fontWeight: 600, color: dark ? '#fafafa' : '#171717' },
        } : undefined,
        tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmtNum(v) },
        legend: yFields.length > 1 ? { bottom: 0, textStyle: { color: textColor } } : undefined,
        grid: { left: 12, right: 20, top: chart.title ? 50 : 20, bottom: 20, containLabel: true },
        xAxis: {
          type: 'category', data: xData,
          axisLabel: { color: textColor, rotate: xData.length > 8 ? 30 : 0, fontSize: 11 },
          axisLine: { lineStyle: { color: lineColor } },
        },
        yAxis: {
          type: 'value', axisLabel: { color: textColor, fontSize: 11 },
          splitLine: { lineStyle: { color: lineColor, type: 'dashed' } },
        },
        series,
      };
    }

    // ---- 默认 bar ----
    const xData = rows.map(r => String(r[xField] ?? ''));
    const series = yFields.map((field, idx) => ({
      name: field,
      type: 'bar',
      itemStyle: { color: PALETTE[idx % PALETTE.length], borderRadius: [4, 4, 0, 0] },
      data: rows.map(r => {
        const v = Number(r[field] ?? 0);
        return Number.isFinite(v) ? v : 0;
      }),
    }));
    return {
      backgroundColor: 'transparent',
      color: PALETTE,
      title: chart.title ? {
        text: chart.title, left: 'center',
        textStyle: { fontSize: 14, fontWeight: 600, color: dark ? '#fafafa' : '#171717' },
      } : undefined,
      tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmtNum(v) },
      legend: yFields.length > 1 ? { bottom: 0, textStyle: { color: textColor } } : undefined,
      grid: { left: 12, right: 20, top: chart.title ? 50 : 20, bottom: yFields.length > 1 ? 40 : 20, containLabel: true },
      xAxis: {
        type: 'category', data: xData,
        axisLabel: { color: textColor, rotate: xData.length > 8 ? 30 : 0, fontSize: 11 },
        axisLine: { lineStyle: { color: lineColor } },
      },
      yAxis: {
        type: 'value', axisLabel: { color: textColor, fontSize: 11 },
        splitLine: { lineStyle: { color: lineColor, type: 'dashed' } },
      },
      series,
    };
  }, [chart, dark, rows, xField, yFields]);

  // 空数据必须显式提示：真空结果与渲染失败在旧实现里不可区分。
  // / An explicit empty state: previously "no rows" and "render failure" looked identical.
  if (rows.length === 0) {
    return (
      <div style={{ height: 320, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description={chart.title ? `${chart.title}：无数据` : '查询结果为空 / No data'} />
      </div>
    );
  }

  // 后端会把 TABLE 规划转为 BAR 下发；万一仍收到 table 类型，明细已由
  // AnalysisMessage 的「查看原始数据」折叠展示，这里不再重复渲染图表。
  // / TABLE plans are converted to BAR by the engine; if a raw table type still arrives,
  // the row detail is already available in the collapsible raw-data section.
  if (chart.type === 'table') {
    return (
      <div style={{ height: 320, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty description="明细结果请展开下方「查看原始数据」查看 / See raw data below" />
      </div>
    );
  }

  return (
    <ReactECharts
      option={option}
      style={{ height: 320, width: '100%' }}
      notMerge
      lazyUpdate
    />
  );
}
