import { useCallback, useEffect, useState } from 'react';
import { Button, Dialog, Input, Select, Textarea, Tag, Loading, InputNumber } from 'tdesign-react';
import { AddIcon, DeleteIcon, RefreshIcon, CheckCircleIcon } from 'tdesign-icons-react';
import { api } from '../api';
import type { Dataset } from '../types';

/** JDBC datasource management panel / JDBC 数据源管理面板. */

interface Props {
  visible: boolean;
  onClose: () => void;
  onImported: (d: Dataset) => void;
}

type Datasource = {
  id: string; name: string; type: string; jdbcUrl: string; username: string;
  status: string; lastTestedAt?: string; createdAt: string;
};

/** 数据源注册与只读导入 / Register and import from external datasources. */
export function DatasourcePanel({ visible, onClose, onImported }: Props) {
  const [datasources, setDatasources] = useState<Datasource[]>([]);
  const [loading, setLoading] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [form, setForm] = useState({ name: '', type: 'mysql', jdbcUrl: '', username: '', password: '' });
  const [testResults, setTestResults] = useState<Record<string, string>>({});
  // 导入对话框 / Import dialog
  const [importFor, setImportFor] = useState<Datasource | null>(null);
  const [importSql, setImportSql] = useState('SELECT * FROM your_table');
  const [importLimit, setImportLimit] = useState(1000);
  const [importName, setImportName] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try { setDatasources(await api.listDatasources()); }
    catch (e) { alert('加载数据源失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { if (visible) load(); }, [visible, load]);

  /** 注册新数据源 / Register a new datasource. */
  const register = async () => {
    if (!form.name.trim() || !form.jdbcUrl.trim()) return;
    setLoading(true);
    try {
      await api.registerDatasource(form);
      setAddOpen(false);
      setForm({ name: '', type: 'mysql', jdbcUrl: '', username: '', password: '' });
      await load();
    } catch (e) { alert('注册失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  /** 测试连接 / Test connection. */
  const test = async (id: string) => {
    setTestResults(prev => ({ ...prev, [id]: '测试中...' }));
    try {
      const r = await api.testDatasource(id);
      setTestResults(prev => ({ ...prev, [id]: r.success
        ? `✓ 连接成功 / OK (${r.catalog || ''} v${r.majorVersion || ''})`
        : `✗ ${r.error || '连接失败'}` }));
      await load();
    } catch (e) { setTestResults(prev => ({ ...prev, [id]: '✗ ' + (e as Error).message })); }
  };

  /** 导入查询结果 / Import query result as dataset. */
  const doImport = async () => {
    if (!importFor || !importSql.trim()) return;
    setLoading(true);
    try {
      const r = await api.importDatasource(importFor.id, {
        sql: importSql, limit: importLimit, name: importName || undefined,
      });
      // 拉取完整元数据后刷新数据集列表；失败时构造最小对象仍能进入列表
      // Fetch full metadata; fall back to a minimal object so the dataset still enters the list.
      let dataset: Dataset;
      try { dataset = await api.getDataset(r.datasetId); }
      catch {
        dataset = { id: r.datasetId, name: importName || importFor.name, fileName: 'jdbc',
          fileType: 'jdbc', rowCount: r.rows, columnCount: r.columns,
          tableName: r.datasetId, columns: [],
          createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
      }
      onImported(dataset);
      setImportFor(null);
    } catch (e) { alert('导入失败 / Failed: ' + (e as Error).message); }
    finally { setLoading(false); }
  };

  /** 删除数据源 / Delete datasource. */
  const remove = async (id: string, name: string) => {
    if (!window.confirm(`确定删除数据源「${name}」吗？`)) return;
    try { await api.deleteDatasource(id); await load(); }
    catch (e) { alert('删除失败 / Failed: ' + (e as Error).message); }
  };

  return (
    <Dialog header="数据源管理 / Datasources" visible={visible} onClose={onClose}
      footer={false} width={760}>
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <p className="text-xs" style={{ color: 'var(--td-text-color-secondary)' }}>
            连接 MySQL/PostgreSQL，以只读 SQL 导入数据集（密码 AES 加密存储）
          </p>
          <div className="flex gap-2">
            <Button size="small" variant="outline" icon={<RefreshIcon />} onClick={load}>刷新</Button>
            <Button size="small" theme="primary" icon={<AddIcon />} onClick={() => setAddOpen(true)}>注册数据源</Button>
          </div>
        </div>

        {loading ? <div className="flex justify-center py-10"><Loading /></div> :
          datasources.length === 0 ? (
            <div className="text-center py-10 text-xs" style={{ color: 'var(--td-text-color-placeholder)' }}>
              暂无数据源，点击「注册数据源」添加 MySQL/PostgreSQL 连接
            </div>
          ) : datasources.map(ds => (
            <div key={ds.id} className="p-3 rounded-xl border"
              style={{ borderColor: 'var(--td-component-stroke)', backgroundColor: 'var(--td-bg-color-container)' }}>
              <div className="flex items-center gap-2 flex-wrap">
                <Tag size="small" theme="primary" variant="light">{ds.type.toUpperCase()}</Tag>
                <span className="text-sm font-medium">{ds.name}</span>
                <Tag size="small" theme={ds.status === 'active' ? 'success' : 'danger'} variant="light">{ds.status}</Tag>
                <span className="text-xs ml-auto" style={{ color: 'var(--td-text-color-placeholder)' }}>
                  {ds.username}@{ds.jdbcUrl}
                </span>
                <Button size="small" variant="text" icon={<CheckCircleIcon />} onClick={() => test(ds.id)}>测试</Button>
                <Button size="small" variant="outline" onClick={() => {
                  setImportFor(ds); setImportName(ds.name); setImportSql('SELECT * FROM your_table');
                }}>导入</Button>
                <Button size="small" variant="text" icon={<DeleteIcon />} onClick={() => remove(ds.id, ds.name)} />
              </div>
              {testResults[ds.id] && (
                <div className="mt-2 text-xs" style={{ color: 'var(--td-text-color-secondary)' }}>{testResults[ds.id]}</div>
              )}
            </div>
          ))}
      </div>

      {/* 注册对话框 / Register dialog */}
      <Dialog header="注册数据源 / Register Datasource" visible={addOpen} onClose={() => setAddOpen(false)}
        onConfirm={register} confirmBtn={{ loading, content: '注册' }}>
        <div className="space-y-3">
          <div><label className="block text-xs mb-1">名称 / Name</label>
            <Input value={form.name} onChange={v => setForm({ ...form, name: v as string })} placeholder="例如：生产 MySQL" /></div>
          <div><label className="block text-xs mb-1">类型 / Type</label>
            <Select value={form.type} onChange={v => setForm({ ...form, type: v as string })}
              options={[{ label: 'MySQL', value: 'mysql' }, { label: 'PostgreSQL', value: 'postgresql' }]} /></div>
          <div><label className="block text-xs mb-1">JDBC URL</label>
            <Input value={form.jdbcUrl} onChange={v => setForm({ ...form, jdbcUrl: v as string })}
              placeholder="jdbc:mysql://host:3306/db" /></div>
          <div className="grid grid-cols-2 gap-3">
            <div><label className="block text-xs mb-1">用户名 / Username</label>
              <Input value={form.username} onChange={v => setForm({ ...form, username: v as string })} /></div>
            <div><label className="block text-xs mb-1">密码 / Password</label>
              <Input type="password" value={form.password} onChange={v => setForm({ ...form, password: v as string })} /></div>
          </div>
        </div>
      </Dialog>

      {/* 导入对话框 / Import dialog */}
      <Dialog header={`导入数据 / Import · ${importFor?.name || ''}`} visible={!!importFor}
        onClose={() => setImportFor(null)} onConfirm={doImport} confirmBtn={{ loading, content: '导入' }}>
        <div className="space-y-3">
          <div><label className="block text-xs mb-1">数据集名称 / Dataset name</label>
            <Input value={importName} onChange={v => setImportName(v as string)} /></div>
          <div><label className="block text-xs mb-1">只读 SQL（SELECT / WITH / SHOW）</label>
            <Textarea value={importSql} onChange={v => setImportSql(v as string)}
              autosize={{ minRows: 4, maxRows: 10 }} /></div>
          <div><label className="block text-xs mb-1">最大行数 / Limit</label>
            <InputNumber value={importLimit} onChange={v => setImportLimit(Number(v) || 1000)}
              min={1} max={10000} /></div>
        </div>
      </Dialog>
    </Dialog>
  );
}
