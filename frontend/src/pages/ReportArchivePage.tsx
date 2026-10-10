import { PageContainer, ProTable, type ProColumns } from '@ant-design/pro-components'
import { App, Dropdown, Space, Tag, Tooltip, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { exportRun, type ExportFormat } from '../api/reports'
import type { components } from '@jabiz/client'
import { formatDateTime } from '../meta/format'

type RunSummary = components['schemas']['RunSummary']
type Verification = components['schemas']['Verification']

const FORMATS: ExportFormat[] = ['pdf', 'xlsx', 'csv']

/**
 * Issued reports (docs/design/19-reports.md section 5): newest first, optionally of one template (`?template=`).
 * Each can be saved again exactly as issued, and verified against the data at its point in time. The server lists
 * only runs the user may read.
 */
export default function ReportArchivePage() {
  const { t } = useTranslation()
  const { message } = App.useApp()
  const [searchParams] = useSearchParams()
  const template = searchParams.get('template') ?? undefined
  const [verdicts, setVerdicts] = useState<Record<string, Verification>>({})

  const verify = async (runId: string) => {
    try {
      const result = await unwrap(api.POST('/api/reports/runs/{runId}/verify', { params: { path: { runId } } }))
      setVerdicts((current) => ({ ...current, [runId]: result }))
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  const save = async (runId: string, format: ExportFormat) => {
    try {
      await exportRun(runId, format)
    } catch (e) {
      message.error(e instanceof ApiError ? e.display : String(e))
    }
  }

  const columns: ProColumns<RunSummary>[] = [
    {
      title: t('reports.archive.report'),
      dataIndex: 'title',
      render: (_, run) => (
        <Space direction="vertical" size={0}>
          <span>{run.title}</span>
          <Typography.Text type="secondary">{run.period}</Typography.Text>
        </Space>
      ),
    },
    {
      title: t('reports.archive.issued'),
      dataIndex: 'issuedTime',
      render: (_, run) => (
        <Space direction="vertical" size={0}>
          <span>{run.issuedTime ? formatDateTime(run.issuedTime) : ''}</span>
          <Typography.Text type="secondary">{run.issuedBy}</Typography.Text>
        </Space>
      ),
    },
    { title: t('reports.archive.rows'), dataIndex: 'rowCount', align: 'right' },
    {
      title: t('reports.archive.hash'),
      dataIndex: 'contentHash',
      render: (_, run) => (
        <Tooltip title={run.contentHash}>
          <Typography.Text code>{run.contentHash?.slice(0, 12)}</Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: t('reports.archive.status'),
      key: 'status',
      render: (_, run) => {
        const verdict = verdicts[run.runId ?? '']
        return (
          <Space wrap>
            {run.supersededBy && <Tag color="default">{t('reports.archive.superseded')}</Tag>}
            {!run.recomputable && (
              <Tooltip title={t('reports.archive.notRecomputableHint')}>
                <Tag>{t('reports.archive.notRecomputable')}</Tag>
              </Tooltip>
            )}
            {verdict && (
              <Tag color={verdict.verdict === 'identical' ? 'green' : 'red'} data-testid={`verdict-${run.runId}`}>
                {t(`reports.archive.verdict.${verdict.verdict}`)}
              </Tag>
            )}
          </Space>
        )
      },
    },
    {
      title: t('list.actions'),
      key: 'actions',
      valueType: 'option',
      render: (_, run) => [
        <Dropdown
          key="save"
          menu={{
            items: FORMATS.map((format) => ({ key: format, label: t(`reports.formats.${format}`) })),
            onClick: ({ key }) => void save(run.runId!, key as ExportFormat),
          }}
        >
          <a data-testid={`run-save-${run.runId}`}>{t('reports.archive.save')}</a>
        </Dropdown>,
        <a key="verify" onClick={() => void verify(run.runId!)} data-testid={`run-verify-${run.runId}`}>
          {t('reports.archive.verify')}
        </a>,
      ],
    },
  ]

  return (
    <PageContainer title={t('reports.archive.title')}>
      <ProTable<RunSummary>
        rowKey="runId"
        columns={columns}
        search={false}
        params={{ template }}
        pagination={false}
        data-testid="report-archive"
        request={async () => {
          try {
            const runs = await unwrap(
              api.GET('/api/reports/runs', { params: { query: { template, limit: 200 } } }),
            )
            return { data: runs, success: true }
          } catch (e) {
            message.error(e instanceof ApiError ? e.display : String(e))
            return { data: [], success: false }
          }
        }}
      />
    </PageContainer>
  )
}
