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

test('status 事件保留 stage 与 message / status event keeps stage and message', () => {
  const event = parseSSEBlock('event: status\ndata: {"stage":"planning","message":"正在生成计划"}');
  assert.ok(event);
  assert.equal(event.type, 'status');
  assert.equal((event as any).stage, 'planning');
  assert.equal((event as any).message, '正在生成计划');
});

test('done 事件保留 sessionId / done event keeps sessionId', () => {
  const event = parseSSEBlock('event: done\ndata: {"sessionId":"s1","messageId":"m1"}');
  assert.equal((event as any).sessionId, 's1');
});

test('error 事件保留 message / error event keeps message', () => {
  const event = parseSSEBlock('event: error\ndata: {"message":"分析失败"}');
  assert.equal((event as any).message, '分析失败');
});

test('init 事件保留模型信息 / init event keeps model info', () => {
  const event = parseSSEBlock('event: init\ndata: {"sessionId":"s1","model":"qwen"}');
  assert.equal((event as any).model, 'qwen');
});

test('标量 data 包装为 content / scalar data wrapped as content', () => {
  const event = parseSSEBlock('event: text\ndata: "hello"');
  assert.equal((event as any).content, 'hello');
});

test('空块返回 null / empty block returns null', () => {
  assert.equal(parseSSEBlock(''), null);
  assert.equal(parseSSEBlock('\n\n'), null);
});

test('CRLF 行尾也能解析 / CRLF line endings are parsed', () => {
  const event = parseSSEBlock('event: status\r\ndata: {"stage":"planning","message":"思考中"}');
  assert.ok(event);
  assert.equal(event.type, 'status');
  assert.equal((event as any).stage, 'planning');
});

test('多行 data 字段按换行合并 / multi-line data fields are joined', () => {
  // 模拟合法 JSON 被拆到两个 data 行 / Simulate valid JSON split across two data lines.
  const event = parseSSEBlock('event: text\ndata: {"content":\ndata: "hello"}');
  assert.ok(event);
  assert.equal((event as any).content, 'hello');
});
