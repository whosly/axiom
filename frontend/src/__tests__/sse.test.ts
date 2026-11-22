import test from 'node:test';
import assert from 'node:assert/strict';
import { parseSSEBlock } from '../sse';

/** SSE 解析单元测试 / Unit tests for SSE block parsing. */

test('plan 事件映射 data 到 plan 字段 / plan event maps data into plan field', () => {
  const event = parseSSEBlock('event: plan\ndata: {"intent":"trend","title":"趋势"}');
  assert.ok(event);
  assert.equal(event.type, 'plan');
  assert.deepEqual((event as any).plan, { intent: 'trend', title: '趋势' });
});

test('result 事件映射 data 到 result 字段 / result event maps data into result field', () => {
  const event = parseSSEBlock('event: result\ndata: {"type":"trend","rows":[{"a":1}]}');
  assert.ok(event);
  assert.equal(event.type, 'result');
  assert.equal((event as any).result.type, 'trend');
});

test('chart 事件映射 data 到 chart 字段 / chart event maps data into chart field', () => {
  const event = parseSSEBlock('event: chart\ndata: {"type":"line","xField":"period"}');
  assert.ok(event);
  assert.equal(event.type, 'chart');
  assert.equal((event as any).chart.type, 'line');
});

test('text 事件展开 content / text event spreads content', () => {
  const event = parseSSEBlock('event: text\ndata: {"content":"正在分析"}');
  assert.ok(event);
  assert.equal(event.type, 'text');
  assert.equal((event as any).content, '正在分析');
});

test('无 event 行时默认 text 类型 / default to text when no event line', () => {
  const event = parseSSEBlock('data: {"message":"hello"}');
  assert.ok(event);
  assert.equal(event.type, 'text');
  assert.equal((event as any).message, 'hello');
});

test('非法 JSON 返回 null / malformed JSON returns null', () => {
  assert.equal(parseSSEBlock('event: status\ndata: {invalid'), null);
});

test('无 data 行返回 null / missing data line returns null', () => {
  assert.equal(parseSSEBlock('event: done'), null);
});
