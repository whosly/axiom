import { useRef, useState } from 'react';
import { Button, Dialog, Textarea, Input, Loading, Tag } from 'tdesign-react';
import { UploadIcon, AddIcon, FileCopyIcon, DeleteIcon, ChartBarIcon, CloudIcon } from 'tdesign-icons-react';
import type { Dataset } from '../types';
import { api } from '../api';
import { DatasourcePanel } from './DatasourcePanel';

interface Props {
  datasets: Dataset[];
  selectedId: string | null;
  loading: boolean;
  onSelect: (id: string) => void;
  onCreated: (d: Dataset) => void;
  onDeleted: (id: string) => void;
  onRefresh: () => void;
}

const TYPE_COLORS: Record<string, string> = {
  numeric: 'blue',
  temporal: 'green',
  categorical: 'orange',
  boolean: 'purple',
};

export function DatasetPanel({
  datasets, selectedId, loading, onSelect, onCreated, onDeleted, onRefresh,
}: Props) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [pasteOpen, setPasteOpen] = useState(false);
  const [pasteText, setPasteText] = useState('');
  const [pasteName, setPasteName] = useState('粘贴数据');
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState<{ dataset: Dataset; rows: Array<Record<string, unknown>> } | null>(null);
  const [datasourceOpen, setDatasourceOpen] = useState(false);

  const handleUpload = async (file: File) => {
    setBusy(true);
    try {
      const d = await api.upload(file);
      onCreated(d);
    } catch (e) {
      alert('上传失败: ' + (e as Error).message);
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const handlePaste = async () => {
    setBusy(true);
    try {
      const d = await api.paste(pasteText, pasteName || '粘贴数据');
      onCreated(d);
      setPasteOpen(false);
      setPasteText('');
    } catch (e) {
      alert('导入失败: ' + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleSample = async () => {
    setBusy(true);
    try {
      const d = await api.createSample();
      onCreated(d);
    } catch (e) {
      alert('创建示例失败: ' + (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (id: string, name: string) => {
    if (!window.confirm(`确定删除数据集「${name}」吗？`)) return;
    try {
      await api.deleteDataset(id);
      onDeleted(id);
    } catch (e) {
      alert('删除失败: ' + (e as Error).message);
    }
  };

  const showPreview = async (d: Dataset) => {
    try {
      const rows = await api.preview(d.id, 10);
      setPreview({ dataset: d, rows });
    } catch (e) {
      alert('预览失败: ' + (e as Error).message);
    }
  };

  return (
    <aside className="w-72 flex-shrink-0 flex flex-col h-full border-r"
      style={{ backgroundColor: 'var(--td-bg-color-container)', borderColor: 'var(--td-component-stroke)' }}>
      <div className="p-4 border-b" style={{ borderColor: 'var(--td-component-stroke)' }}>
        <div className="flex items-center gap-2 mb-3">
          <div className="w-8 h-8 rounded-lg flex items-center justify-center"
            style={{ background: 'linear-gradient(135deg, #0052d9, #00a870)' }}>
            <ChartBarIcon size={18} color="white" />
          </div>
          <div>
            <div className="text-sm font-semibold" style={{ color: 'var(--td-text-color-primary)' }}>数据集</div>
            <div className="text-xs" style={{ color: 'var(--td-text-color-placeholder)' }}>上传数据开始分析</div>
          </div>
        </div>
        <div className="grid grid-cols-3 gap-2">
          <Button size="small" variant="outline" icon={<UploadIcon />} onClick={() => fileRef.current?.click()}>上传</Button>
          <Button size="small" variant="outline" icon={<AddIcon />} onClick={() => setPasteOpen(true)}>粘贴</Button>
          <Button size="small" variant="outline" icon={<FileCopyIcon />} onClick={handleSample}>示例</Button>
        </div>
        <Button size="small" variant="text" icon={<CloudIcon />} className="w-full mt-1"
          onClick={() => setDatasourceOpen(true)}>数据库直连 / JDBC</Button>
        <input
          ref={fileRef}
          type="file"
          accept=".csv,.json,.xlsx,.xls,.tsv,.txt"
          className="hidden"
          onChange={e => e.target.files?.[0] && handleUpload(e.target.files[0])}
        />
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {loading ? (
          <div className="flex justify-center py-8"><Loading size="small" /></div>
        ) : datasets.length === 0 ? (
          <div className="text-center py-10 px-4">
            <div className="text-4xl mb-3">📊</div>
            <p className="text-xs leading-relaxed" style={{ color: 'var(--td-text-color-placeholder)' }}>
              还没有数据集。上传 CSV / Excel / JSON，或点击「示例」快速体验。
            </p>
          </div>
        ) : (
          datasets.map(d => (
            <div
              key={d.id}
              className={`group p-3 mb-1 rounded-lg cursor-pointer transition-colors ${selectedId === d.id ? 'ring-1' : ''}`}
              style={{
                backgroundColor: selectedId === d.id ? 'var(--td-brand-color-light)' : 'transparent',
                borderColor: 'var(--td-brand-color)',
              }}
              onClick={() => onSelect(d.id)}
            >
              <div className="flex items-start justify-between gap-1">
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium truncate" style={{ color: 'var(--td-text-color-primary)' }} title={d.name}>
                    {d.name}
                  </div>
                  <div className="text-xs mt-1" style={{ color: 'var(--td-text-color-placeholder)' }}>
                    {d.rowCount} 行 · {d.columnCount} 列 · {d.fileType}
                  </div>
                </div>
                <div className="flex gap-1">
                  <Button size="small" variant="outline" onClick={e => { e.stopPropagation(); showPreview(d); }}>查看原始数据</Button>
                  <Button className="opacity-0 group-hover:opacity-100 transition-opacity" size="small" variant="text"
                    icon={<DeleteIcon />} onClick={e => { e.stopPropagation(); handleDelete(d.id, d.name); }} />
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      <Dialog header="粘贴数据" visible={pasteOpen} onClose={() => setPasteOpen(false)}
        onConfirm={handlePaste} confirmBtn={{ loading: busy, content: '导入' }}>
        <div className="space-y-3">
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--td-text-color-secondary)' }}>名称</label>
            <Input value={pasteName} onChange={v => setPasteName(v as string)} placeholder="数据集名称" />
          </div>
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--td-text-color-secondary)' }}>
              数据内容（支持 CSV / TSV / JSON 数组）
            </label>
            <Textarea value={pasteText} onChange={v => setPasteText(v as string)}
              placeholder={'日期,产品,销售额\n2026-01-01,产品A,12000\n2026-01-02,产品B,8000'}
              autosize={{ minRows: 8, maxRows: 16 }} />
          </div>
        </div>
      </Dialog>

      <Dialog header={preview ? `数据预览 · ${preview.dataset.name}` : ''} visible={!!preview}
        onClose={() => setPreview(null)} footer={false} width={800}>
        {preview && (
          <div className="overflow-auto max-h-[60vh]">
            {/* 数据流转说明 / Data flow note */}
            <div className="mb-4 p-3 rounded-lg text-xs leading-relaxed" style={{ backgroundColor: 'var(--td-brand-color-light)' }}>
              <div className="font-medium mb-1" style={{ color: 'var(--td-brand-color)' }}>数据流转 / Data Flow</div>
              <span style={{ color: 'var(--td-text-color-secondary)' }}>
                这里是<b>原始数据</b>（存于 DuckDB）。对它提问后，分析结果（图表+结论）显示在对话中；
                分析完成后会自动沉淀为<b>知识库案例</b>，可在「知识库 → 详情」中查看处理链路。
                <br/>This is the <b>raw data</b> (in DuckDB). Analysis results appear in chat and are
                distilled into <b>knowledge cases</b> (see Knowledge → Detail for lineage).
              </span>
            </div>
            <div className="flex flex-wrap gap-1 mb-3">
              {preview.dataset.columns.map(c => (
                <Tag key={c.name} size="small" theme={TYPE_COLORS[c.type] as 'blue' | 'green' | 'orange' | 'purple' as never} variant="light">
                  {c.name} · {c.type}
                </Tag>
              ))}
            </div>
            <table className="w-full text-xs" style={{ borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {preview.dataset.columns.map(c => (
                    <th key={c.name} className="p-2 text-left font-medium"
                      style={{ backgroundColor: 'var(--td-bg-color-component)', borderBottom: '1px solid var(--td-component-stroke)' }}>
                      {c.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {preview.rows.map((row, i) => (
                  <tr key={i}>
                    {preview.dataset.columns.map(c => (
                      <td key={c.name} className="p-2"
                        style={{ borderBottom: '1px solid var(--td-component-stroke)' }}>
                        {String(row[c.name] ?? '-')}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Dialog>

      <DatasourcePanel visible={datasourceOpen} onClose={() => setDatasourceOpen(false)}
        onImported={onCreated} />

      {(busy) && <div className="absolute inset-0 flex items-center justify-center bg-black/10"><Loading /></div>}
    </aside>
  );
}
