import { PageContainer, ProForm, type ProFormInstance } from '@ant-design/pro-components'
import { InboxOutlined } from '@ant-design/icons'
import {
  Alert, App, Button, Card, Input, InputNumber, Popconfirm, Result, Select, Space, Spin, Steps, Switch, Table,
  Typography, Upload,
} from 'antd'
import { useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { uploadFile } from '../api/files'
import {
  commitImport, inspectFile, previewImport, type ImportInspection, type ImportMapping, type ImportOptions,
  type ImportReport,
} from '../api/imports'
import { ApiError } from '../api/problem'
import { renderNode } from '../components/SchemaInputs'
import { useImportCatalog } from '../meta/hooks'
import { InvalidJson, inputNodes, toProcessInput, type JsonSchema } from '../meta/processForm'
import ImportReportView, { IssueTable } from './ImportReportView'
import { paths } from './paths'

const DELIMITERS = [',', ';', '\t', '|']

/**
 * Importing a file (docs/design/20-imports.md): upload it, map its columns to the import's fields (adjusting a CSV or
 * Excel layout and using saved mappings), give the parameters, preview - every row is run and rolled back - and
 * commit, which imports everything or, when anything is wrong, nothing. The server checks everything again.
 */
export default function ImportPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  const id = searchParams.get('id') ?? ''
  const catalog = useImportCatalog()
  const entry = catalog.data?.find((i) => i.id === id)
  const nodes = useMemo(() => (entry?.params ? inputNodes(entry.params as JsonSchema) : []), [entry])
  const paramsForm = useRef<ProFormInstance>(undefined)

  const [file, setFile] = useState<{ fileId: string; name: string } | null>(null)
  const [inspection, setInspection] = useState<ImportInspection | null>(null)
  const [options, setOptions] = useState<ImportOptions>({})
  const [columns, setColumns] = useState<Record<string, string | undefined>>({})
  const [constants, setConstants] = useState<Record<string, string>>({})
  const [report, setReport] = useState<ImportReport | null>(null)
  const [outcome, setOutcome] = useState<{ committed: boolean; report: ImportReport } | null>(null)
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState<{ name?: string; mapping?: ImportMapping }[]>([])
  const [mappingName, setMappingName] = useState('')

  const fail = (e: unknown) => message.error(e instanceof ApiError ? e.display : String(e))

  const inspect = async (fileId: string, adjusted: ImportOptions) => {
    const result = await inspectFile(id, fileId, adjusted)
    setInspection(result)
    setColumns((current) => ({ ...(result.suggested ?? {}), ...current }))
    setReport(null)
  }

  const loadSaved = async () => {
    try {
      setSaved(await unwrap(api.GET('/api/imports/{importId}/mappings', { params: { path: { importId: id } } })))
    } catch {
      setSaved([])
    }
  }

  const upload = async (blob: File) => {
    if (!entry?.filePolicy) return
    setBusy(true)
    try {
      const uploaded = await uploadFile(entry.filePolicy, blob, blob.name)
      setFile({ fileId: uploaded.fileId!, name: blob.name })
      setColumns({})
      setConstants({})
      setOutcome(null)
      await inspect(uploaded.fileId!, options)
      await loadSaved()
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  const mapping = (): ImportMapping => ({
    columns: Object.fromEntries(Object.entries(columns).filter(([, column]) => column)) as Record<string, string>,
    constants: Object.fromEntries(Object.entries(constants).filter(([, value]) => value !== '')),
    options,
  })

  const params = (): Record<string, unknown> | undefined => {
    if (nodes.length === 0) return undefined
    return toProcessInput(nodes, paramsForm.current?.getFieldsValue() ?? {})
  }

  const run = async (commit: boolean) => {
    if (!file) return
    setBusy(true)
    try {
      if (nodes.length > 0) await paramsForm.current?.validateFields()
      if (commit) {
        const result = await commitImport(id, file.fileId, mapping(), params(), notes || undefined)
        setOutcome(result)
        setReport(result.report)
      } else {
        setReport(await previewImport(id, file.fileId, mapping(), params()))
      }
    } catch (e) {
      if (e instanceof InvalidJson) message.error(t('process.invalidJson'))
      else if (!(e && typeof e === 'object' && 'errorFields' in e)) fail(e)
    } finally {
      setBusy(false)
    }
  }

  const saveMapping = async () => {
    if (!mappingName.trim()) return
    try {
      await unwrap(api.PUT('/api/imports/{importId}/mappings/{name}', {
        params: { path: { importId: id, name: mappingName.trim() } },
        body: { mapping: mapping() },
      }))
      message.success(t('imports.mappingSaved'))
      await loadSaved()
    } catch (e) {
      fail(e)
    }
  }

  const applySaved = async (name: string) => {
    const found = saved.find((m) => m.name === name)?.mapping
    if (!found || !file) return
    const adjusted = found.options ?? {}
    setOptions(adjusted)
    setColumns(found.columns ?? {})
    setConstants(found.constants ?? {})
    setMappingName(name)
    try {
      await inspect(file.fileId, adjusted)
    } catch (e) {
      fail(e)
    }
  }

  if (catalog.isLoading) return <Spin style={{ margin: 48 }} />
  if (!entry) return <Result status="404" title={id} />
  const format = entry.format
  const step = outcome ? 3 : report ? 2 : file ? 1 : 0

  return (
    <PageContainer
      title={<span data-testid="page-title">{entry.title}</span>}
      extra={[<Link key="runs" to={paths.importRuns(id)} data-testid="import-runs-link">{t('imports.runs.title')}</Link>]}
    >
      <Steps
        current={step}
        style={{ marginBottom: 24 }}
        items={[
          { title: t('imports.steps.upload') },
          { title: t('imports.steps.map') },
          { title: t('imports.steps.preview') },
          { title: t('imports.steps.done') },
        ]}
      />
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Card title={t('imports.steps.upload')}>
          <Upload.Dragger
            accept={(entry.extensions ?? []).join(',')}
            showUploadList={false}
            disabled={busy}
            customRequest={({ file: blob }) => void upload(blob as File)}
            data-testid="import-upload"
          >
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p>{file ? file.name : t('imports.drop')}</p>
            <Typography.Text type="secondary">{(entry.extensions ?? []).join(' ')}</Typography.Text>
          </Upload.Dragger>
        </Card>

        {file && inspection && (
          <Card title={t('imports.steps.map')}>
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              {format?.adjustable && (
                <Space wrap data-testid="import-layout">
                  {format.kind === 'csv' && (
                    <>
                      <span>{t('imports.layout.delimiter')}</span>
                      <Select
                        style={{ width: 90 }}
                        value={options.delimiter ?? format.delimiter}
                        onChange={(value) => setOptions({ ...options, delimiter: value })}
                        options={DELIMITERS.map((d) => ({ value: d, label: d === '\t' ? 'TAB' : d }))}
                        data-testid="import-delimiter"
                      />
                      <span>{t('imports.layout.skipLines')}</span>
                      <InputNumber min={0} value={options.skipLines ?? format.skipLines}
                        onChange={(value) => setOptions({ ...options, skipLines: value ?? 0 })} data-testid="import-skip" />
                      <span>{t('imports.layout.charset')}</span>
                      <Select style={{ width: 150 }} value={options.charset ?? format.charset}
                        onChange={(value) => setOptions({ ...options, charset: value })}
                        options={(format.charsets ?? []).map((c) => ({ value: c, label: c }))} />
                    </>
                  )}
                  {format.kind === 'xlsx' && (
                    <>
                      <span>{t('imports.layout.sheet')}</span>
                      <Input style={{ width: 160 }} value={options.sheet ?? format.sheet ?? ''}
                        onChange={(e) => setOptions({ ...options, sheet: e.target.value || undefined })} />
                      <span>{t('imports.layout.headerRow')}</span>
                      <InputNumber min={0} value={options.headerRow ?? format.headerRow}
                        onChange={(value) => setOptions({ ...options, headerRow: value ?? 1 })} />
                    </>
                  )}
                  <span>{t('imports.layout.header')}</span>
                  <Switch checked={options.header ?? format.header ?? true}
                    onChange={(value) => setOptions({ ...options, header: value })} />
                  <Button onClick={() => void inspect(file.fileId, options).catch(fail)} data-testid="import-reread">
                    {t('imports.layout.reread')}
                  </Button>
                </Space>
              )}
              {(inspection.issues ?? []).length > 0 && <IssueTable entry={entry} issues={inspection.issues ?? []} />}
              {saved.length > 0 && (
                <Space>
                  <span>{t('imports.savedMappings')}</span>
                  <Select style={{ width: 220 }} placeholder={t('imports.choose')} onChange={(name) => void applySaved(name)}
                    options={saved.map((m) => ({ value: m.name, label: m.name }))} data-testid="import-saved" />
                </Space>
              )}
              <Table
                size="small"
                rowKey="name"
                pagination={false}
                dataSource={entry.fields ?? []}
                data-testid="import-mapping"
                columns={[
                  {
                    title: t('imports.field'),
                    dataIndex: 'label',
                    render: (label: string, field) => (
                      <span>{label}{field.required && <Typography.Text type="danger"> *</Typography.Text>}</span>
                    ),
                  },
                  {
                    title: t('imports.column'),
                    key: 'column',
                    render: (_, field) => (
                      <Select
                        allowClear
                        style={{ minWidth: 200 }}
                        value={columns[field.name!]}
                        onChange={(value) => setColumns({ ...columns, [field.name!]: value })}
                        options={(inspection.columns ?? []).map((c) => ({ value: c, label: c }))}
                        data-testid={`import-column-${field.name}`}
                      />
                    ),
                  },
                  {
                    title: t('imports.constant'),
                    key: 'constant',
                    render: (_, field) => (
                      <Input
                        value={constants[field.name!] ?? ''}
                        onChange={(e) => setConstants({ ...constants, [field.name!]: e.target.value })}
                        data-testid={`import-constant-${field.name}`}
                      />
                    ),
                  },
                ]}
              />
              {entry.mappings && (
                <Space>
                  <Input placeholder={t('imports.mappingName')} value={mappingName}
                    onChange={(e) => setMappingName(e.target.value)} data-testid="import-mapping-name" />
                  <Button onClick={() => void saveMapping()} disabled={!mappingName.trim()}>{t('imports.saveMapping')}</Button>
                </Space>
              )}
              <Typography.Text type="secondary">
                {t('imports.sample', { count: inspection.records ?? 0 })}
              </Typography.Text>
              <Table
                size="small"
                rowKey="number"
                pagination={false}
                scroll={{ x: 'max-content' }}
                dataSource={inspection.sample ?? []}
                data-testid="import-sample"
                columns={[
                  { title: '#', dataIndex: 'location', width: 110 },
                  ...(inspection.columns ?? []).map((c) => ({
                    title: c,
                    key: c,
                    render: (_: unknown, r: { cells?: Record<string, string> }) => r.cells?.[c] ?? '',
                  })),
                ]}
              />
              {nodes.length > 0 && (
                <Card size="small" title={t('imports.parameters')}>
                  <ProForm formRef={paramsForm} submitter={false} layout="inline" dateFormatter={false}>
                    {nodes.map((node) => renderNode(node, t))}
                  </ProForm>
                </Card>
              )}
              <Button type="primary" loading={busy} onClick={() => void run(false)} disabled={Boolean(outcome?.committed)}
                data-testid="import-preview">
                {t('imports.preview')}
              </Button>
            </Space>
          </Card>
        )}

        {report && (
          <Card title={outcome ? t('imports.steps.done') : t('imports.steps.preview')}>
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              {outcome?.committed && (
                <Result
                  status="success"
                  title={t('imports.committed')}
                  subTitle={report.runId}
                  extra={<Link to={paths.importRuns(id)}>{t('imports.runs.title')}</Link>}
                />
              )}
              <ImportReportView entry={entry} report={report} />
              {!outcome?.committed && (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Input.TextArea
                    rows={2}
                    maxLength={4000}
                    placeholder={t('imports.notes')}
                    value={notes}
                    onChange={(e) => setNotes(e.target.value)}
                    data-testid="import-notes"
                  />
                  {!report.accepted && <Alert type="warning" showIcon message={t('imports.fixFirst')} />}
                  <Popconfirm
                    title={t('imports.commitConfirm')}
                    okText={t('imports.commit')}
                    cancelText={t('reports.cancel')}
                    onConfirm={() => void run(true)}
                    disabled={!report.accepted}
                  >
                    <Button type="primary" danger loading={busy} disabled={!report.accepted} data-testid="import-commit">
                      {t('imports.commit')}
                    </Button>
                  </Popconfirm>
                </Space>
              )}
            </Space>
          </Card>
        )}
      </Space>
    </PageContainer>
  )
}
