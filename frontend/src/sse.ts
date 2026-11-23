import type { SSEEvent } from './types';

/**
 * 解析单个 SSE 块为前端事件 / Parse a single SSE block into a frontend event.
 *
 * 后端具名事件把 payload 直接放在 data 字段（而非嵌套 result/plan），
 * 且 data.type 表示分析意图而非事件类型，因此需按 event name 映射到对应字段。
 * Named SSE events carry payload directly in `data`; `data.type` is the analysis
 * intent, not the event type, so we map by event name into the expected field.
 *
 * @param block SSE 文本块 / Raw SSE block text
 * @returns 解析后的事件，非法块返回 null / Parsed event, or null for malformed blocks
 */
export function parseSSEBlock(block: string): SSEEvent | null {
  // 兼容 CRLF 行尾，并合并多行 data: 字段（SSE 规范用换行连接）。
  // Tolerate CRLF and join multi-line data fields per the SSE spec.
  const lines = block.split(/\r?\n/);
  const eventLine = lines.find(l => l.startsWith('event:'));
  const dataLines = lines.filter(l => l.startsWith('data:'));
  if (dataLines.length === 0) return null;
  const payload = dataLines.map(l => l.slice(5).trimStart()).join('\n').trim();
  try {
    const data = JSON.parse(payload);
    const type = eventLine ? eventLine.slice(6).trim() : 'text';
    switch (type) {
      case 'plan': return { type: 'plan', plan: data } as SSEEvent;
      case 'result': return { type: 'result', result: data } as SSEEvent;
      case 'chart': return { type: 'chart', chart: data } as SSEEvent;
      default: return { type, ...(typeof data === 'object' ? data : { content: data }) } as SSEEvent;
    }
  } catch {
    // 忽略格式错误的 SSE 块 / Ignore malformed SSE blocks.
    return null;
  }
}
