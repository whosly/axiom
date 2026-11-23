import { lazy, Suspense, useState } from 'react';
import { Tag, Loading } from 'tdesign-react';
import { ChevronDownIcon, ChartBarIcon, TipsIcon, CheckCircleIcon } from 'tdesign-icons-react';
import type { ChatMessage } from '../types';
import { INTENT_LABELS } from '../types';

// ECharts 体积较大，按需加载避免阻塞首屏 / Lazy-load the heavy ECharts bundle.
const AnalysisChart = lazy(() => import('./AnalysisChart').then(m => ({ default: m.AnalysisChart })));

interface Props {
  message: ChatMessage;
  dark?: boolean;
}

export function AnalysisMessage({ message, dark }: Props) {
  const [showData, setShowData] = useState(false);

  if (message.role === 'user') {
    return (
      <div className="flex justify-end mb-6">
        <div className="max-w-[75%] px-4 py-3 rounded-2xl rounded-tr-md text-sm leading-relaxed"
          style={{ backgroundColor: 'var(--td-brand-color)', color: 'white' }}>
          {message.content}
        </div>
      </div>
    );
  }

  const result = message.result;
  const plan = message.plan;

  return (
    <div className="flex gap-3 mb-6">
      <div className="w-9 h-9 flex-shrink-0 rounded-full flex items-center justify-center"
        style={{ background: 'linear-gradient(135deg, #0052d9, #00a870)' }}>
        <ChartBarIcon size={18} color="white" />
      </div>
      <div className="flex-1 min-w-0 max-w-[85%] space-y-3">
        {message.stage && !result && (
          <div className="flex items-center gap-2 text-sm px-4 py-3 rounded-xl"
            style={{ backgroundColor: 'var(--td-bg-color-component)', color: 'var(--td-text-color-secondary)' }}>
            <span className="animate-pulse">●</span> {message.stage}
          </div>
        )}

        {plan && (
          <div className="flex items-center gap-2 flex-wrap">
            <Tag theme="primary" variant="light" size="small">
              {INTENT_LABELS[plan.intent] || plan.intent}
            </Tag>
            {plan.title && <span className="text-sm font-medium" style={{ color: 'var(--td-text-color-primary)' }}>{plan.title}</span>}
            {plan.reasoning && (
              <span className="text-xs" style={{ color: 'var(--td-text-color-placeholder)' }} title={plan.reasoning}>
                · {plan.reasoning.slice(0, 40)}{plan.reasoning.length > 40 ? '...' : ''}
              </span>
            )}
          </div>
        )}

        {result?.chart && result.rows.length > 0 && (
          <div className="rounded-xl p-4 border"
            style={{ backgroundColor: 'var(--td-bg-color-container)', borderColor: 'var(--td-component-stroke)' }}>
            <Suspense fallback={<div className="flex justify-center py-10"><Loading /></div>}>
              <AnalysisChart chart={result.chart} dark={dark} />
            </Suspense>
          </div>
        )}

        {result?.insights && result.insights.length > 0 && (
          <div className="rounded-xl p-4"
            style={{ backgroundColor: 'var(--td-brand-color-light)' }}>
            <div className="flex items-center gap-2 text-sm font-medium mb-2"
              style={{ color: 'var(--td-brand-color)' }}>
              <TipsIcon size={16} /> 关键洞察
            </div>
            <ul className="space-y-1.5">
              {result.insights.map((insight, i) => (
                <li key={i} className="flex items-start gap-2 text-xs leading-relaxed"
                  style={{ color: 'var(--td-text-color-primary)' }}>
                  <CheckCircleIcon size={14} className="mt-0.5 flex-shrink-0" style={{ color: 'var(--td-brand-color)' }} />
                  {insight}
                </li>
              ))}
            </ul>
          </div>
        )}

        {result && result.rows.length > 0 && (
          <div>
            <button onClick={() => setShowData(!showData)}
              className="flex items-center gap-1 text-xs px-2 py-1 rounded"
              style={{ color: 'var(--td-text-color-secondary)', backgroundColor: 'var(--td-bg-color-component)' }}>
              <ChevronDownIcon size={14} style={{ transform: showData ? 'rotate(180deg)' : 'none' }} />
              查看原始数据（{result.rows.length} 行）
            </button>
            {showData && (
              <div className="mt-2 overflow-auto max-h-64 rounded-lg border"
                style={{ borderColor: 'var(--td-component-stroke)' }}>
                <table className="w-full text-xs">
                  <thead className="sticky top-0">
                    <tr>
                      {Object.keys(result.rows[0]).map(k => (
                        <th key={k} className="p-2 text-left whitespace-nowrap font-medium"
                          style={{ backgroundColor: 'var(--td-bg-color-component)', borderBottom: '1px solid var(--td-component-stroke)' }}>
                          {k}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {result.rows.slice(0, 50).map((row, i) => (
                      <tr key={i}>
                        {Object.keys(result.rows[0]).map(k => (
                          <td key={k} className="p-2 whitespace-nowrap"
                            style={{ borderBottom: '1px solid var(--td-component-stroke)' }}>
                            {typeof row[k] === 'number' ? (row[k] as number).toFixed(2) : String(row[k] ?? '-')}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {message.content && (
          <div className="px-4 py-3 rounded-2xl rounded-tl-md text-sm leading-relaxed whitespace-pre-wrap"
            style={{ backgroundColor: 'var(--td-bg-color-component)', color: 'var(--td-text-color-primary)' }}>
            {message.content}
            {message.streaming && <span className="animate-pulse ml-0.5">▋</span>}
          </div>
        )}

        {message.streaming && !message.content && !message.stage && (
          <div className="flex items-center gap-2 text-sm px-4 py-3 rounded-xl"
            style={{ backgroundColor: 'var(--td-bg-color-component)', color: 'var(--td-text-color-secondary)' }}>
            <span className="animate-pulse">●</span> 思考中...
          </div>
        )}
      </div>
    </div>
  );
}
