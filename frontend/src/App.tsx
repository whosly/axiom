import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Select, Tooltip, Loading, Tag, Dialog, Input } from 'tdesign-react';
import { RefreshIcon, SunnyIcon, MoonIcon, SendIcon, StopIcon, ChatIcon, DeleteIcon, BookIcon, EditIcon } from 'tdesign-icons-react';
import '@tdesign-react/chat/es/style/index.js';

import { api } from './api';
import { APP_CONFIG } from './config';
import type { Dataset, ChatSession, ChatMessage, SSEEvent } from './types';
import { DatasetPanel } from './components/DatasetPanel';
import { AnalysisMessage } from './components/AnalysisMessage';
import { KnowledgePanel } from './components/KnowledgePanel';
import { LlmConfigPanel } from './components/LlmConfigPanel';

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
  const [selectedModel, setSelectedModel] = useState('deepseek-chat');
  // 用 ref 持有 AbortController，保证 stop 按钮能中断正在进行的 SSE 请求
  // Hold the AbortController in a ref so the stop button can cancel the in-flight SSE request.
  const abortRef = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const selectedDataset = datasets.find(d => d.id === selectedDatasetId) || null;

  const loadDatasets = useCallback(async () => {
    setDatasetsLoading(true);
    try {
      const list = await api.listDatasets();
      setDatasets(list);
      if (list.length && !selectedDatasetId) setSelectedDatasetId(list[0].id);
    } catch (e) {
      console.error('load datasets failed', e);
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
      setSelectedModel(m.defaultModel || m.models[0]?.modelId || 'deepseek-chat');
    }).catch(e => console.error('load models failed', e));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
  }, [dark]);

  const openSession = async (id: string) => {
    try {
      const s = await api.getSession(id);
      setCurrentSessionId(s.id);
      setSelectedDatasetId(s.datasetId);
      setMessages(s.messages || []);
    } catch (e) { console.error(e); }
  };

  const startNewChat = () => {
    setCurrentSessionId(null);
    setMessages([]);
  };

  const sendMessage = async () => {
    const question = input.trim();
    if (!question || !selectedDatasetId || isLoading) return;

    const userMsg: ChatMessage = {
      id: 'u-' + Date.now(), role: 'user', content: question,
      createdAt: new Date().toISOString(),
    };
    const assistantMsg: ChatMessage = {
      id: 'a-' + Date.now(), role: 'assistant', content: '',
      createdAt: new Date().toISOString(), streaming: true,
    };
    setMessages(prev => [...prev, userMsg, assistantMsg]);
    setInput('');
    setIsLoading(true);

    // 每次请求使用独立的 AbortController，避免中断旧请求时误伤新请求
    // Use a fresh controller per request so aborting never cancels a newer request.
    const controller = new AbortController();
    abortRef.current = controller;

    const updateAssistant = (updater: (m: ChatMessage) => ChatMessage) => {
      setMessages(prev => prev.map(m => m.id === assistantMsg.id ? updater(m) : m));
    };

    try {
      await api.chat({ sessionId: currentSessionId || undefined, datasetId: selectedDatasetId,
        message: question, model: selectedModel }, (event: SSEEvent) => {
        switch (event.type) {
          case 'init':
            setCurrentSessionId(event.sessionId);
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
            updateAssistant(m => ({
              ...m,
              result: { ...(m.result || { type: '', title: '', rows: [] }), chart: event.chart },
            }));
            break;
          case 'text':
            updateAssistant(m => ({ ...m, content: m.content + event.content, stage: undefined }));
            break;
          case 'done':
            updateAssistant(m => ({ ...m, streaming: false }));
            loadSessions();
            break;
          case 'error':
            updateAssistant(m => ({ ...m, content: '分析失败：' + event.message, streaming: false, stage: undefined }));
            break;
        }
      }, controller.signal);
      updateAssistant(m => ({ ...m, streaming: false }));
    } catch (e) {
      // 主动取消属于用户操作，不展示为错误；仅将流式光标收尾
      // An intentional abort is a user action, not an error; only stop the streaming cursor.
      const aborted = controller.signal.aborted;
      updateAssistant(m => ({
        ...m,
        content: aborted ? (m.content || '已停止生成') : '请求失败：' + (e as Error).message,
        streaming: false,
        stage: undefined,
      }));
    } finally {
      abortRef.current = null;
      setIsLoading(false);
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
    } catch (e) { alert('重命名失败 / Failed: ' + (e as Error).message); }
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
      <DatasetPanel
        datasets={datasets}
        selectedId={selectedDatasetId}
        loading={datasetsLoading}
        onSelect={id => { setSelectedDatasetId(id); startNewChat(); }}
        onCreated={handleDatasetCreated}
        onDeleted={handleDatasetDeleted}
        onRefresh={loadDatasets}
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
                      await api.deleteSession(currentSessionId);
                      setSessions(prev => prev.filter(s => s.id !== currentSessionId));
                      startNewChat();
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
                <span className="text-3xl font-bold text-white">D</span>
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
                  onChange={v => setSelectedModel(v as string)}
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
        onUpdated={() => api.models().then(m => setModels(m.models)).catch(() => {})} />
    </div>
  );
}

export default App;
