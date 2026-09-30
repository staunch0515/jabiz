import { PageContainer, ProTable, type ProColumns } from '@ant-design/pro-components'
import { App, Button, Drawer, Dropdown, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { exportImportRun, type ExportFormat, type ImportRunDetail, type ImportRunSummary } from '../api/imports'
import { ApiError } from '../api/problem'
import { formatDateTime } from '../meta/format'
import { useImportCatalog } from '../meta/hooks'
import { IssueTable } from './ImportReportView'

const FORMATS: ExportFormat[] = ['pdf', 'xlsx', 'csv']

/**
 * Committed imports and rejected attempts (docs/design/20-imports.md section 5), newest first, optionally of one
 * import (`?import=`): figures, notes, problems, and the report as a file. The server lists only runs of imports the
 * user may run.
 */
export default function ImportRunsPage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  const importId = searchParams.get('import') ?? undefined
  const catalog = useImportCatalog()
  const [detail, setDetail] = useState<ImportRunDetail | null>(null)

  const fail = (e: unknown) => message.error(e instanceof ApiError ? e.display : String(e))

  const open = async (runId: string) => {
    try {
      setDetail(await unwrap(api.GET('/api/imports/runs/{runId}', { params: { path: { runId } } })))
    } catch (e) {
      fail(e)
    }
  }

  const save = async (runId: string, format: ExportFormat) => {
    try {
      await exportImportRun(runId, format)
    } catch (e) {
      fail(e)
    }
  }

  const columns: ProColumns<ImportRunSummary>[] = [
    { title: t('imports.runs.import'), dataIndex: 'title' },
    {
      title: t('imports.runs.imported'),
      dataIndex: 'importedTime',
      render: (_, run) => (
        <Space direction="vertical" size={0}>
          <span>{run.importedTime ? formatDateTime(run.importedTime) : ''}</span>
          <Typography.Text type="secondary">{run.importedBy}</Typography.Text>
        </Space>
      ),
    },
    {
      title: t('imports.runs.outcome'),
      dataIndex: 'outcome',
      render: (_, run) => (
        <Tag color={run.outcome === 'committed' ? 'green' : 'red'} data-testid={`run-outcome-${run.runId}`}>
          {t(`imports.runs.outcomes.${run.outcome}`)}
        </Tag>
      ),
    },
    { title: t('imports.rows'), dataIndex: 'rows', align: 'right' },
    { title: t('imports.duplicates'), dataIndex: 'duplicates', align: 'right' },
    { title: t('imports.runs.problems'), dataIndex: 'issueCount', align: 'right' },
    { title: t('imports.notes'), dataIndex: 'notes', ellipsis: true },
    {
      title: '',
      key: 'actions',
      render: (_, run) => (
        <Space>
          <Button size="small" onClick={() => void open(run.runId!)} data-testid={`run-open-${run.runId}`}>
            {t('imports.runs.details')}
          </Button>
          <Dropdown
            menu={{
              items: FORMATS.map((format) => ({ key: format, label: t(`reports.formats.${format}`) })),
              onClick: ({ key }) => void save(run.runId!, key as ExportFormat),
            }}
          >
            <Button size="small" data-testid={`run-save-${run.runId}`}>{t('reports.export')}</Button>
          </Dropdown>
        </Space>
      ),
    },
  ]

  const entry = catalog.data?.find((i) => i.id === detail?.run?.importId)
  return (
    <PageContainer title={t('imports.runs.title')}>
      <ProTable<ImportRunSummary>
        rowKey="runId"
        search={false}
        columns={columns}
        pagination={{ defaultPageSize: 50 }}
        data-testid="import-runs"
        // The import filter comes from the URL: a new one asks again.
        params={{ importId }}
        request={async ({ importId: current }) => {
          const data = await unwrap(api.GET('/api/imports/runs', {
            params: { query: { import: current as string | undefined, limit: 200 } },
          }))
          return { data, success: true, total: data.length }
        }}
      />
      <Drawer width={900} open={detail !== null} onClose={() => setDetail(null)} title={detail?.run?.title}>
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Typography.Text>{detail.run?.notes}</Typography.Text>
            <Typography.Text type="secondary" copyable>{detail.run?.sha256}</Typography.Text>
            <IssueTable entry={entry} issues={detail.issues ?? []} />
          </Space>
        )}
      </Drawer>
    </PageContainer>
  )
}
