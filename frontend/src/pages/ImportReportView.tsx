import { Alert, Descriptions, Segmented, Space, Table, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ImportEntry, ImportIssue, ImportReport } from '../api/imports'
import { fieldLabel } from '../meta/imports'

const STATUS_COLORS: Record<string, string> = { ok: 'green', duplicate: 'default', error: 'red' }

/** Problems of an import, a run or an inspection: where, which field, what. */
export function IssueTable({ entry, issues }: { entry?: ImportEntry; issues: ImportIssue[] }) {
  const { t } = useTranslation()
  return (
    <Table<ImportIssue>
      size="small"
      rowKey={(issue) => `${issue.row}-${issue.field}-${issue.code}-${issue.message}`}
      dataSource={issues}
      pagination={{ pageSize: 20, hideOnSinglePage: true }}
      data-testid="import-issues"
      columns={[
        { title: t('imports.row'), dataIndex: 'row', width: 70, render: (row: number) => (row ? row : t('imports.file')) },
        { title: t('imports.location'), dataIndex: 'location', width: 140 },
        { title: t('imports.field'), dataIndex: 'field', width: 160, render: (field: string) => fieldLabel(entry, field) },
        { title: t('imports.problem'), dataIndex: 'message' },
      ]}
    />
  )
}

/**
 * What a preview or a commit found (docs/design/20-imports.md section 5): the figures, the control totals, every
 * problem, and each record with its outcome.
 */
export default function ImportReportView({ entry, report }: { entry?: ImportEntry; report: ImportReport }) {
  const { t } = useTranslation()
  const [show, setShow] = useState<'all' | 'error' | 'duplicate'>('all')
  const fields = entry?.fields ?? []
  const results = useMemo(
    () => (report.results ?? []).filter((r) => show === 'all' || r.status === show),
    [report.results, show],
  )
  const issues = report.issues ?? []
  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle" data-testid="import-report">
      {report.accepted
        ? <Alert type="success" showIcon message={t(report.committed ? 'imports.committed' : 'imports.accepted')} />
        : <Alert type="error" showIcon message={t('imports.problems', { count: issues.length })}
            description={report.runId && !report.committed ? t('imports.rejected') : undefined} />}
      <Descriptions size="small" bordered column={{ xs: 1, sm: 2, lg: 4 }}>
        <Descriptions.Item label={t('imports.records')}>{report.records}</Descriptions.Item>
        <Descriptions.Item label={t('imports.rows')}>{report.rows}</Descriptions.Item>
        <Descriptions.Item label={t('imports.duplicates')}>{report.duplicates}</Descriptions.Item>
        <Descriptions.Item label={t('imports.processed')}>{`${report.processed} / ${report.units}`}</Descriptions.Item>
        {Object.entries(report.totals ?? {}).map(([field, total]) => (
          <Descriptions.Item key={field} label={`${t('imports.total')} ${fieldLabel(entry, field)}`}>
            <span data-testid={`import-total-${field}`}>{String(total)}</span>
          </Descriptions.Item>
        ))}
      </Descriptions>
      {issues.length > 0 && <IssueTable entry={entry} issues={issues} />}
      <Segmented
        value={show}
        onChange={(value) => setShow(value as typeof show)}
        options={[
          { value: 'all', label: t('imports.show.all') },
          { value: 'error', label: t('imports.show.error') },
          { value: 'duplicate', label: t('imports.show.duplicate') },
        ]}
      />
      <Table
        size="small"
        rowKey="number"
        dataSource={results}
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        data-testid="import-rows"
        columns={[
          { title: t('imports.row'), dataIndex: 'number', width: 70 },
          {
            title: t('imports.status'),
            dataIndex: 'status',
            width: 110,
            render: (status: string) => <Tag color={STATUS_COLORS[status]}>{t(`imports.statuses.${status}`)}</Tag>,
          },
          ...fields.map((field) => ({
            title: field.label,
            key: field.name,
            render: (_: unknown, row: { values?: Record<string, unknown> }) => {
              const value = row.values?.[field.name ?? '']
              return value === null || value === undefined ? '' : <Typography.Text>{String(value)}</Typography.Text>
            },
          })),
        ]}
      />
    </Space>
  )
}
