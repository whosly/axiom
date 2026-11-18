import { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { ChartSpec } from '../types';

interface Props {
  chart: ChartSpec;
  dark?: boolean;
}

const PALETTE = ['#0052d9', '#00a870', '#ed7b2f', '#8e56ff', '#e34d59', '#0594fa'];

export function AnalysisChart({ chart, dark }: Props) {
  const option = useMemo(() => {
    const rows = chart.series || [];
    // tolerate both camelCase and all-lowercase field names from different serializers
    const c = chart as unknown as Record<string, unknown>;
    const xField = chart.xField || (c.xfield as string) || '';
    const yFieldsRaw = chart.yFields || (c.yfields as string[]);
    const xData = rows.map(r => String(r[xField] ?? ''));
    const yFields = yFieldsRaw?.length ? yFieldsRaw : ['value'];

    const series = yFields.map((field, idx) => ({
      name: field,
      type: chart.type === 'line' ? 'line' : chart.type === 'pie' ? 'pie' : chart.type === 'scatter' ? 'scatter' : 'bar',
      data: rows.map(r => {
        const v = r[field];
        return typeof v === 'number' ? v : Number(v ?? 0);
      }),
      smooth: chart.type === 'line',
      symbolSize: chart.type === 'scatter' ? 12 : undefined,
      itemStyle: { color: PALETTE[idx % PALETTE.length], borderRadius: chart.type === 'bar' ? [4, 4, 0, 0] : 0 },
      areaStyle: chart.type === 'line' ? { opacity: 0.12 } : undefined,
      label: chart.type === 'pie' ? { show: true, formatter: '{b}: {d}%' } : undefined,
    }));

    const textColor = dark ? '#a3a3a3' : '#737373';
    const lineColor = dark ? '#404040' : '#e5e5e5';

    return {
      backgroundColor: 'transparent',
      color: PALETTE,
      title: chart.title ? {
        text: chart.title,
        left: 'center',
        textStyle: { fontSize: 14, fontWeight: 600, color: dark ? '#fafafa' : '#171717' },
      } : undefined,
      tooltip: { trigger: chart.type === 'pie' ? 'item' : 'axis' },
      legend: yFields.length > 1 || chart.type === 'pie'
        ? { bottom: 0, textStyle: { color: textColor } }
        : undefined,
      grid: { left: 12, right: 20, top: chart.title ? 50 : 20, bottom: yFields.length > 1 ? 40 : 20, containLabel: true },
      xAxis: chart.type === 'pie' ? undefined : {
        type: chart.type === 'scatter' ? 'value' : 'category',
        data: chart.type === 'scatter' ? undefined : xData,
        axisLabel: { color: textColor, rotate: xData.length > 8 ? 30 : 0, fontSize: 11 },
        axisLine: { lineStyle: { color: lineColor } },
      },
      yAxis: chart.type === 'pie' ? undefined : {
        type: chart.type === 'scatter' ? 'category' : 'value',
        data: chart.type === 'scatter' ? xData : undefined,
        axisLabel: { color: textColor, fontSize: 11 },
        splitLine: { lineStyle: { color: lineColor, type: 'dashed' } },
      },
      series: chart.type === 'pie'
        ? [{ type: 'pie', radius: '60%', data: rows.map((r, i) => ({
            name: String(r[xField] ?? ''),
            value: Number(r[yFields[0]] ?? 0),
            itemStyle: { color: PALETTE[i % PALETTE.length] },
          })), label: { show: true, formatter: '{b}: {d}%' } }]
        : series,
    };
  }, [chart, dark]);

  return (
    <ReactECharts
      option={option}
      style={{ height: 320, width: '100%' }}
      notMerge
      lazyUpdate
    />
  );
}
