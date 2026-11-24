import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Select, Tooltip, Tag, Dialog, Input } from 'tdesign-react';
import { RefreshIcon, SunnyIcon, MoonIcon, SendIcon, StopIcon, ChatIcon, DeleteIcon, BookIcon, EditIcon } from 'tdesign-icons-react';
import '@tdesign-react/chat/es/style/index.js';

import { api } from './api';
import { APP_CONFIG } from './config';
import type { Dataset, ChatSession, ChatMessage, SSEEvent } from './types';
import { DatasetPanel } from './components/DatasetPanel';
import { AnalysisMessage } from './components/AnalysisMessage';
import { KnowledgePanel } from './components/KnowledgePanel';
import { LlmConfigPanel } from './components/LlmConfigPanel';
import { notifyError } from './notify';

const SUGGESTIONS = [
  '分析销售额的变化趋势',
  '检测销售额中的异常数据',
  '对比各产品的销售额',
  '按地区统计订单数',
  '给出数据的整体概览',
];

function App() {
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [selectedDatasetId, setSelectedDatasetId] = useState<string | null>(null);
  const [datasetsLoading, setDatasetsLoading] = useState(true);
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [dark, setDark] = useState(false);
  const [showKnowledge, setShowKnowledge] = useState(false);
  // 会话重命名 / Session rename dialog
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameTitle, setRenameTitle] = useState('');
  // LLM 运行时配置 / Runtime LLM config dialog
  const [configOpen, setConfigOpen] = useState(false);
  // 可用模型列表（来自 /api/models），避免硬编码 / Available models from API instead of hardcoding
  const [models, setModels] = useState<Array<{ modelId: string; name: string }>>([]);
  const [selectedModel, setSelectedModel] = useState('deepseek');
  // 用 ref 持有 AbortController，保证 stop 按钮能中断正在进行的 SSE 请求
  // Hold the AbortController in a ref so the stop button can cancel the in-flight SSE request.
  const abortRef = useRef<AbortController | null>(null);
  // 会话令牌：切换会话/新对话时递增，旧流的事件回调据此自我失效，
  // 防止切换后旧流的 init/status/text 反向覆写新会话的状态（串会话）。
  // Monotonic chat token: old streams self-invalidate after a switch, so their events
  // can never overwrite the newly selected session's state.
  const chatTokenRef = useRef(0);
  // 会话加载令牌：防止快速连点历史会话时旧响应覆盖新选择（竞态保护）。
  // Monotonic token guarding openSession against out-of-order responses.
  const openSessionTokenRef = useRef(0);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  // 加载失败与空数据必须区分，否则接口挂掉会被误显示成「还没有数据集」。
  // Distinguish load failure from empty data so a broken API is not shown as "no datasets".
  const [loadError, setLoadError] = useState<string | null>(null);

  const selectedDataset = datasets.find(d => d.id === selectedDatasetId) || null;

  const loadDatasets = useCallback(async () => {
    setDatasetsLoading(true);
    setLoadError(null);
    try {
      const list = await api.listDatasets();
      setDatasets(list);
      if (list.length && !selectedDatasetId) setSelectedDatasetId(list[0].id);
    } catch (e) {
      console.error('load datasets failed', e);
      setLoadError('数据集加载失败 / Failed to load datasets: ' + (e as Error).message);
    } finally {
      setDatasetsLoading(false);
    }
  }, [selectedDatasetId]);

  const loadSessions = useCallback(async () => {
    try { setSessions(await api.listSessions()); } catch (e) { console.error(e); }
  }, []);

  useEffect(() => {
    loadDatasets();
    loadSessions();
    // 加载模型列表，失败时保持默认模型 / Load models; keep default on failure.
    api.models().then(m => {
      setModels(m.models);
      setSelectedModel(m.defaultModel || m.models[0]?.modelId || 'deepseek');
    }).catch(e => console.error('load models failed', e));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /** 切换 LLM 厂商（热更新后端配置）/ Switch LLM provider via runtime config. */
  const switchProvider = async (provider: string) => {
    const previous = selectedModel;
    setSelectedModel(provider);
    try {
      await api.updateLlmConfig({ provider });
    } catch (e) {
      setSelectedModel(previous);
      notifyError('切换模型失败 / Failed: ' + (e as Error).message);
    }
  };
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
  }, [dark]);

  const openSession = async (id: string) => {
    // 切换会话必须中止进行中的流：否则旧流的 init 会把 currentSessionId 改回去，
    // 用户随后发送的消息会写进旧会话。/ Aborting on switch is mandatory — otherwise the
    // old stream's init event flips currentSessionId back and new messages leak into it.
    abortRef.current?.abort();
    const token = ++openSessionTokenRef.current;
    setIsLoading(true);
    try {
      const s = await api.getSession(id);
      // 竞态保护：请求期间用户又点了另一个会话，丢弃本次过期响应。
      // Stale-response guard: a newer selection wins, drop this outdated one.
      if (openSessionTokenRef.current !== token) return;
      setCurrentSessionId(s.id);
      setSelectedDatasetId(s.datasetId);
      setMessages(s.messages || []);
    } catch (e) { console.error(e); }
    finally { if (openSessionTokenRef.current === token) setIsLoading(false); }
  };

  const startNewChat = () => {
    abortRef.current?.abort();
    chatTokenRef.current++;
    openSessionTokenRef.current++;
    setCurrentSessionId(null);
    setMessages([]);
  };

  const sendMessage = async () => {
    const question = input.trim();
    if (!question || !selectedDatasetId || isLoading) return;

    // 用 UUID 避免同一毫秒连续发送产生重复 React key；init 事件到达后再换成后端持久化 ID。
    // UUID avoids duplicate React keys on rapid sends; swap to the server ID once init arrives.
    let userMsgId = 'u-' + crypto.randomUUID();
    const userMsg: ChatMessage = {
      id: userMsgId, role: 'user', content: question,
      createdAt: new Date().toISOString(),
    };
    const assistantMsg: ChatMessage = {
      id: 'a-' + crypto.randomUUID(), role: 'assistant', content: '',
      createdAt: new Date().toISOString(), streaming: true,
    };
    setMessages(prev => [...prev, userMsg, assistantMsg]);
    setInput('');
    setIsLoading(true);

    // 每次请求使用独立的 AbortController + 递增令牌；切换会话后旧流的回调全部失效。
    // Fresh controller per request plus a monotonically increasing token: after a session
    // switch every callback of the old stream self-invalidates.
    const controller = new AbortController();
    abortRef.current = controller;
    const myToken = ++chatTokenRef.current;
    const alive = () => chatTokenRef.current === myToken;

    const updateAssistant = (updater: (m: ChatMessage) => ChatMessage) => {
      setMessages(prev => prev.map(m => m.id === assistantMsg.id ? updater(m) : m));
    };

    try {
      await api.chat({ sessionId: currentSessionId || undefined, datasetId: selectedDatasetId,
        message: question, model: selectedModel }, (event: SSEEvent) => {
        // 会话已切换：旧流的所有事件一律丢弃，绝不能写入新会话的状态。
        // After a switch every event of the old stream must be discarded.
        if (!alive()) return;
        switch (event.type) {
          case 'init':
            setCurrentSessionId(event.sessionId);
            // 用后端持久化的用户消息 ID 替换乐观 ID，避免后续刷新/删除时 ID 对不上。
            // Replace the optimistic ID with the persisted one for later refresh/delete operations.
            if (event.userMessageId && event.userMessageId !== userMsgId) {
              const oldId = userMsgId;
              userMsgId = event.userMessageId;
              setMessages(prev => prev.map(m => m.id === oldId ? { ...m, id: userMsgId } : m));
            }
            break;
          case 'status':
            updateAssistant(m => ({ ...m, stage: event.message }));
            break;
          case 'plan':
            updateAssistant(m => ({ ...m, plan: event.plan, stage: undefined }));
            break;
          case 'result':
            updateAssistant(m => ({ ...m, result: event.result, stage: undefined }));
            break;
          case 'chart':
            // result 事件已携带 chart（同一份），这里只在缺失时补写，
            // 避免 notMerge 的 ECharts 被重置两次（图例/缩放状态被清空并闪烁）。
            // / result already carries the same chart; only backfill when missing so
            // the ECharts option is not reset twice (legend/zoom state would flash).
            updateAssistant(m => m.result?.chart ? m
              : { ...m, result: { ...(m.result || { type: '', title: '', rows: [] }), chart: event.chart } });
            break;
          case 'text':
            updateAssistant(m => ({ ...m, content: m.content + event.content, stage: undefined }));
            break;
          case 'done':
            // 用后端持久化的助手消息 ID 校准乐观 ID；此后再无本流事件，替换是安全的。
            // Swap in the persisted assistant message id; this is the last event of the stream.
            setMessages(prev => prev.map(m => m.id === assistantMsg.id
              ? { ...m, id: event.messageId, streaming: false } : m));
            loadSessions();
            break;
          case 'error':
            updateAssistant(m => ({ ...m, content: '分析失败：' + event.message, streaming: false, stage: undefined }));
            break;
        }
      }, controller.signal);
      if (alive()) updateAssistant(m => ({ ...m, streaming: false }));
    } catch (e) {
      // 主动取消属于用户操作，不展示为错误；仅将流式光标收尾。
      // 真实失败则移除乐观消息并恢复输入，让用户能直接重试。
      // On real failure, remove optimistic messages and restore input so the user can retry.
      if (!alive()) return; // 已切换会话：一切 UI 恢复都交给新会话流程 / A newer session owns the UI now.
      const aborted = controller.signal.aborted;
      if (aborted) {
        updateAssistant(m => ({ ...m, content: m.content || '已停止生成', streaming: false, stage: undefined }));
      } else {
        setMessages(prev => prev.filter(m => m.id !== userMsgId && m.id !== assistantMsg.id));
        setInput(question);
        notifyError('请求失败 / Request failed: ' + (e as Error).message);
      }
    } finally {
      // 只有当前流仍是活跃流时才清理全局状态；否则把控制权让给切换后的新流程。
      // Only the active stream may reset global state; a switched-away stream must not.
      if (alive()) {
        abortRef.current = null;
        setIsLoading(false);
      }
    }
  };

  /** 停止当前 SSE 请求 / Abort the current SSE request. */
  const stopGeneration = () => {
    abortRef.current?.abort();
  };

  /** 重命名当前会话 / Rename the current session. */
  const renameSession = async () => {
    if (!currentSessionId || !renameTitle.trim()) return;
    try {
      await api.renameSession(currentSessionId, renameTitle.trim());
      setSessions(prev => prev.map(s => s.id === currentSessionId ? { ...s, title: renameTitle.trim() } : s));
      setRenameOpen(false);
    } catch (e) { notifyError('重命名失败 / Failed: ' + (e as Error).message); }
  };

  const handleDatasetCreated = (d: Dataset) => {
    setDatasets(prev => [d, ...prev.filter(x => x.id !== d.id)]);
    setSelectedDatasetId(d.id);
    startNewChat();
  };

  const handleDatasetDeleted = (id: string) => {
    setDatasets(prev => prev.filter(x => x.id !== id));
    if (selectedDatasetId === id) setSelectedDatasetId(datasets.find(x => x.id !== id)?.id || null);
    startNewChat();
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden"
      style={{ backgroundColor: 'var(--td-bg-color-page)' }}>
      {loadError && (
        <div className="absolute top-2 left-2 z-50 px-3 py-1 rounded text-sm"
          style={{ background: 'var(--td-error-color-1)', color: 'var(--td-error-color-6)' }}>
          {loadError} <button onClick={loadDatasets} className="underline">重试</button>
        </div>
      )}
      <DatasetPanel
        datasets={datasets}
        selectedId={selectedDatasetId}
        loading={datasetsLoading}
        onSelect={id => { setSelectedDatasetId(id); startNewChat(); }}
        onCreated={handleDatasetCreated}
        onDeleted={handleDatasetDeleted}
      />

      <main className="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <header className="h-14 flex items-center justify-between px-5 border-b flex-shrink-0"
          style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
          <div className="flex items-center gap-3">
            <Button variant="text" icon={<ChatIcon />} onClick={startNewChat}>新对话</Button>
            <span className="text-sm font-medium" style={{ color: 'var(--td-text-color-primary)' }}>
              {APP_CONFIG.name}
            </span>
            {selectedDataset && (
              <Tag size="small" theme="primary" variant="light">
                {selectedDataset.name} · {selectedDataset.rowCount} 行
              </Tag>
            )}
          </div>
          <div className="flex items-center gap-1">
            <Button variant={showKnowledge ? 'base' : 'text'} size="small" icon={<BookIcon />}
              onClick={() => setShowKnowledge(!showKnowledge)}>知识库</Button>
            <Select size="small" value={currentSessionId || undefined} placeholder="历史会话"
              style={{ width: 180 }}
              onChange={v => v && openSession(v as string)}
              options={sessions.map(s => ({ label: s.title, value: s.id }))} />
            {currentSessionId && (
              <>
                <Tooltip content="重命名当前会话">
                  <Button variant="text" size="small" icon={<EditIcon />}
                    onClick={() => {
                      const s = sessions.find(x => x.id === currentSessionId);
                      setRenameTitle(s?.title || '');
                      setRenameOpen(true);
                    }} />
                </Tooltip>
                <Tooltip content="删除当前会话">
                  <Button variant="text" size="small" icon={<DeleteIcon />}
                    onClick={async () => {
                      try {
                        await api.deleteSession(currentSessionId);
                        setSessions(prev => prev.filter(s => s.id !== currentSessionId));
                        startNewChat();
                      } catch (e) { notifyError('删除会话失败: ' + (e as Error).message); }
                    }} />
                </Tooltip>
              </>
            )}
            <Tooltip content="刷新数据集">
              <Button variant="text" size="small" icon={<RefreshIcon />} onClick={loadDatasets} />
            </Tooltip>
            <Tooltip content={dark ? '浅色模式' : '深色模式'}>
              <Button variant="text" size="small" icon={dark ? <SunnyIcon /> : <MoonIcon />}
                onClick={() => setDark(!dark)} />
            </Tooltip>
          </div>
        </header>

       {showKnowledge ? (
         <div className="flex-1 min-h-0">
           <KnowledgePanel datasetId={selectedDatasetId} onClose={() => setShowKnowledge(false)} />
         </div>
       ) : (
       <>
        {/* Messages */}
        <div className="flex-1 overflow-y-auto px-6 py-6">
          {messages.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center max-w-2xl mx-auto">
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mb-5 shadow-lg"
                style={{ background: 'linear-gradient(135deg, #0052d9, #00a870)' }}>
                <span className="text-3xl font-bold text-white">{APP_CONFIG.nameInitial}</span>
              </div>
              <h2 className="text-2xl font-semibold mb-2" style={{ color: 'var(--td-text-color-primary)' }}>
                {APP_CONFIG.name}
              </h2>
              <p className="text-sm mb-8" style={{ color: 'var(--td-text-color-secondary)' }}>
                {selectedDataset ? `已选择「${selectedDataset.name}」，用自然语言提问即可开始分析` : '请先在左侧选择或上传一个数据集'}
              </p>
              <div className="grid grid-cols-2 gap-3 w-full">
                {SUGGESTIONS.map(s => (
                  <button key={s} disabled={!selectedDataset}
                    onClick={() => setInput(s)}
                    className="text-left px-4 py-3 rounded-xl border text-sm transition-all hover:shadow-md disabled:opacity-40"
                    style={{ backgroundColor: 'var(--td-bg-color-container)', borderColor: 'var(--td-component-stroke)',
                      color: 'var(--td-text-color-primary)' }}>
                    {s}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="max-w-4xl mx-auto">
              {messages.map(m => <AnalysisMessage key={m.id} message={m} dark={dark} />)}
              <div ref={messagesEndRef} />
            </div>
          )}
        </div>

        {/* Input */}
        <div className="border-t px-6 py-4 flex-shrink-0"
          style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
          <div className="max-w-4xl mx-auto">
            {/* 模型选择 / Model selector */}
            {models.length > 0 && (
              <div className="flex items-center gap-2 mb-2 text-xs" style={{ color: 'var(--td-text-color-secondary)' }}>
                <span>模型 / Model</span>
                <Select size="small" value={selectedModel} disabled={isLoading}
                  onChange={v => switchProvider(v as string)}
                  options={models.map(m => ({ label: m.name, value: m.modelId }))} style={{ width: 200 }} />
                <Button size="small" variant="text" onClick={() => setConfigOpen(true)}>配置 / Config</Button>
              </div>
            )}
            <div className="flex gap-2 items-end">
              <textarea
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } }}
                placeholder={selectedDataset ? '用自然语言提问，例如：分析各地区的销售额趋势...' : '请先选择数据集'}
                disabled={!selectedDataset || isLoading}
                rows={2}
                className="flex-1 resize-none px-4 py-3 rounded-xl border outline-none text-sm focus:ring-2"
                style={{ backgroundColor: 'var(--td-bg-color-component)', borderColor: 'var(--td-component-stroke)',
                  color: 'var(--td-text-color-primary)' }}
              />
              {isLoading ? (
                <Button variant="outline" icon={<StopIcon />} onClick={stopGeneration}>
                  停止
                </Button>
              ) : (
                <Button theme="primary" icon={<SendIcon />} disabled={!input.trim() || !selectedDataset}
                  onClick={sendMessage}>发送</Button>
              )}
            </div>
            <p className="text-xs mt-2 text-center" style={{ color: 'var(--td-text-color-placeholder)' }}>
              Enter 发送 · Shift+Enter 换行 · 支持查询、趋势、异常、对比、概览五类分析
            </p>
          </div>
        </div>
       </>
       )}
      </main>

      {/* 重命名会话对话框 / Rename session dialog */}
      <Dialog header="重命名会话 / Rename Session" visible={renameOpen}
        onClose={() => setRenameOpen(false)} onConfirm={renameSession}
        confirmBtn={{ content: '保存 / Save' }}>
        <Input value={renameTitle} onChange={v => setRenameTitle(v as string)}
          placeholder="输入新标题 / New title" onEnter={() => renameSession()} />
      </Dialog>

      <LlmConfigPanel visible={configOpen} onClose={() => setConfigOpen(false)}
        onUpdated={() => api.models().then(m => {
          setModels(m.models);
          // 配置面板改了厂商/模型后同步主界面选择，避免新会话写入过时的模型名。
          // Sync the main selector after config changes so new sessions do not record a stale model.
          setSelectedModel(m.defaultModel || m.models[0]?.modelId || 'deepseek');
        }).catch(() => {})} />
    </div>
  );
}

export default App;
