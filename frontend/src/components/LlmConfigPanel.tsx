import { useEffect, useState } from 'react';
import { Dialog, Input, Tag, Loading, Select } from 'tdesign-react';
import { api } from '../api';
import { notifyError } from '../notify';

/** 运行时 LLM 配置面板 / Runtime LLM configuration panel. */

interface Props {
  visible: boolean;
  onClose: () => void;
  onUpdated?: () => void;
}

/** 热更新 LLM 参数（无需重启）/ Hot-update LLM parameters without restart. */
export function LlmConfigPanel({ visible, onClose, onUpdated }: Props) {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    baseUrl: '', chatModel: '', reasonerModel: '', apiKey: '',
    temperature: '', maxTokens: '',
  });
  const [masked, setMasked] = useState('');
  const [provider, setProvider] = useState('deepseek');
  const [options, setOptions] = useState<Array<{ label: string; value: string }>>([]);

  const loadConfig = async () => {
    const c = await api.getLlmConfig();
    setForm({
      baseUrl: String(c.baseUrl || ''),
      chatModel: String(c.chatModel || ''),
      reasonerModel: String(c.reasonerModel || ''),
      apiKey: '',
      temperature: String(c.temperature ?? ''),
      maxTokens: String(c.maxTokens ?? ''),
    });
    setMasked(String(c.apiKeyMasked || (c.apiKeySet ? '已设置' : '未设置')));
    setProvider(String(c.provider || 'deepseek'));
    setOptions(((c.availableProviders || []) as Array<{ name: string; modelId: string }>)
      .map(p => ({ label: p.name, value: p.modelId })));
  };

  useEffect(() => {
    if (!visible) return;
    setLoading(true);
    loadConfig().catch(e => notifyError('加载配置失败 / Failed: ' + (e as Error).message))
      .finally(() => setLoading(false));
  }, [visible]);

  /** 切换厂商并重新加载该厂商配置 / Switch vendor and reload its config. */
  const switchProvider = async (value: string) => {
    setLoading(true);
    try {
      await api.updateLlmConfig({ provider: value });
      await loadConfig();
      onUpdated?.();
    } catch (e) { notifyError('切换失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  const save = async () => {
    setSaving(true);
    try {
      const body: Record<string, string> = {};
      if (form.baseUrl) body.baseUrl = form.baseUrl;
      if (form.chatModel) body.chatModel = form.chatModel;
      if (form.reasonerModel) body.reasonerModel = form.reasonerModel;
      if (form.apiKey) body.apiKey = form.apiKey;
      if (form.temperature) body.temperature = form.temperature;
      if (form.maxTokens) body.maxTokens = form.maxTokens;
      const r = await api.updateLlmConfig(body);
      setMasked(String(r.apiKeyMasked || masked));
      onUpdated?.();
      onClose();
    } catch (e) { notifyError('保存失败 / Failed: ' + (e as Error).message); }
    finally { setSaving(false); }
  };

  return (
    <Dialog header="模型配置 / LLM Configuration" visible={visible} onClose={onClose}
      onConfirm={save} confirmBtn={{ loading: saving, content: '保存并生效 / Save' }}>
      {loading ? <div className="flex justify-center py-10"><Loading /></div> : (
        <div className="space-y-3">
          <div><label className="block text-xs mb-1">厂商 / Provider</label>
            <Select value={provider} onChange={v => switchProvider(v as string)} options={options} /></div>
          <div className="flex items-center gap-2">
            <Tag theme="success" variant="light">热更新 / Hot reload</Tag>
            <span className="text-xs" style={{ color: 'var(--td-text-color-secondary)' }}>
              当前 API Key: {masked}
            </span>
          </div>
          <div><label className="block text-xs mb-1">Base URL</label>
            <Input value={form.baseUrl} onChange={v => setForm({ ...form, baseUrl: v as string })}
              placeholder="https://api.deepseek.com" /></div>
          <div className="grid grid-cols-2 gap-3">
            <div><label className="block text-xs mb-1">Chat 模型</label>
              <Input value={form.chatModel} onChange={v => setForm({ ...form, chatModel: v as string })} /></div>
            <div><label className="block text-xs mb-1">Reasoner 模型</label>
              <Input value={form.reasonerModel} onChange={v => setForm({ ...form, reasonerModel: v as string })} /></div>
          </div>
          <div><label className="block text-xs mb-1">API Key（留空则不修改 / blank to keep）</label>
            <Input type="password" value={form.apiKey} onChange={v => setForm({ ...form, apiKey: v as string })}
              placeholder="sk-..." /></div>
          <div className="grid grid-cols-2 gap-3">
            <div><label className="block text-xs mb-1">Temperature</label>
              <Input value={form.temperature} onChange={v => setForm({ ...form, temperature: v as string })} /></div>
            <div><label className="block text-xs mb-1">Max Tokens</label>
              <Input value={form.maxTokens} onChange={v => setForm({ ...form, maxTokens: v as string })} /></div>
          </div>
        </div>
      )}
    </Dialog>
  );
}
