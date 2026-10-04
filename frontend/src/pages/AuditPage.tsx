import { PageContainer, ProTable, type ProColumns } from '@ant-design/pro-components'
import { Space, Table, Tabs, Tag, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { AUDIT_FILTERS, type AuditRecord, type AuditFieldChange, type RevealRecord } from '../api/audit'
import { api, unwrap } from '../api/client'
import { expandIcon } from '../components/expandIcon'
import { formatDateTime } from '../meta/format'

const ACTION_COLORS: Record<string, string> = { INSERT: 'green', UPDATE: 'blue', DELETE: 'red' }

type Filters = Partial<Record<(typeof AUDIT_FILTERS)[number], string>> & { recordedTime?: [string, string] }

function show(value: unknown): string {
  if (value === null || value === undefined) return ''
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

/** Each changed field of one record, before and after. Secrets arrive as *** from the server. */
export function ChangeTable({ record }: { record: AuditRecord }) {
  const { t } = useTranslation()
  const rows = Object.entries(record.changes ?? {}).map(([field, change]) => ({ field, ...(change as AuditFieldChange) }))
  return (
    <Table
      size="small"
      rowKey="field"
      pagination={false}
      dataSource={rows}
      data-testid={`audit-changes-${record.recordNo}`}
      columns={[
        { title: t('audit.field'), dataIndex: 'field', width: 200 },
        {
          title: t('audit.before'),
          dataIndex: 'before',
          render: (value: unknown) => <Typography.Text delete={record.action !== 'INSERT'}>{show(value)}</Typography.Text>,
        },
        { title: t('audit.after'), dataIndex: 'after', render: (value: unknown) => show(value) },
      ]}
    />
  )
}

/**
 * The audit trail (docs/design/21-audit-retention.md section 1): who changed which entry, when, why, and each field's
 * value before and after, newest first. Filters start from the URL (`?entityType=&entityId=`, as the history page
 * links here); the server requires audit.read.
 */
export default function AuditPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const initial = Object.fromEntries(
    AUDIT_FILTERS.map((name) => [name, searchParams.get(name) ?? undefined]).filter(([, value]) => value),
  )

  const columns: ProColumns<AuditRecord>[] = [
    {
      title: t('audit.recorded'),
      dataIndex: 'recordedTime',
      valueType: 'dateTimeRange',
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <span>{record.recordedTime ? formatDateTime(record.recordedTime) : ''}</span>
          <Typography.Text type="secondary">{record.actorId}</Typography.Text>
        </Space>
      ),
    },
    { title: t('audit.actor'), dataIndex: 'actorId', hideInTable: true },
    { title: t('audit.entityType'), dataIndex: 'entityType' },
    { title: t('audit.entityId'), dataIndex: 'entityId', copyable: true },
    {
      title: t('audit.action'),
      dataIndex: 'action',
      search: false,
      render: (_, record) => <Tag color={ACTION_COLORS[record.action ?? '']}>{record.action}</Tag>,
    },
    {
      title: t('audit.version'),
      dataIndex: 'versionNo',
      search: false,
      align: 'right',
    },
    { title: t('audit.process'), dataIndex: 'processName', render: (_, record) => record.processName ?? '' },
    { title: t('audit.field'), dataIndex: 'field', hideInTable: true },
    {
      title: t('audit.changed'),
      key: 'changed',
      search: false,
      render: (_, record) => Object.keys(record.changes ?? {}).join(', '),
    },
    { title: t('audit.reason'), dataIndex: 'reason', search: false, ellipsis: true },
  ]

  const records = (
      <ProTable<AuditRecord, Filters>
        rowKey="recordNo"
        columns={columns}
        form={{ initialValues: initial, syncToUrl: false }}
        pagination={{ defaultPageSize: 50 }}
        expandable={{ expandedRowRender: (record) => <ChangeTable record={record} />, expandIcon: expandIcon(t) }}
        data-testid="audit-records"
        request={async ({ current = 1, pageSize = 50, recordedTime, ...filters }) => {
          const query: Record<string, string | number | undefined> = {
            offset: (current - 1) * pageSize,
            limit: pageSize,
          }
          for (const name of AUDIT_FILTERS) {
            const value = (filters as Filters)[name]
            if (value) query[name] = value
          }
          // The approvals of an entry belong to its trail: who approved, what and why.
          if (query.entityType && query.entityId) query.withApprovals = 'true'
          if (recordedTime) {
            query.from = new Date(recordedTime[0]).toISOString()
            query.to = new Date(recordedTime[1]).toISOString()
          }
          const page = await unwrap(api.GET('/api/audit/records', { params: { query } }))
          return { data: page.items ?? [], success: true, total: page.total ?? 0 }
        }}
      />
  )

  return (
    <PageContainer title={t('audit.title')}>
      <Tabs
        items={[
          { key: 'records', label: t('audit.records'), children: records },
          { key: 'reveals', label: t('audit.reveals'), children: <RevealRecords /> },
        ]}
      />
    </PageContainer>
  )
}

type RevealFilters = { actorId?: string; entityType?: string; entityId?: string; revealedAt?: [string, string] }

/**
 * Every display of masked values in plain text (docs/design/10-security.md section 13.1): one value on request, or
 * the plain columns of a template run or an export, with who and when.
 */
function RevealRecords() {
  const { t } = useTranslation()
  const columns: ProColumns<RevealRecord>[] = [
    {
      title: t('audit.revealedAt'),
      dataIndex: 'revealedAt',
      valueType: 'dateTimeRange',
      render: (_, record) => (record.revealedAt ? formatDateTime(record.revealedAt) : ''),
    },
    { title: t('audit.actor'), dataIndex: 'actorId' },
    {
      title: t('audit.revealKind'),
      dataIndex: 'kind',
      search: false,
      render: (_, record) => <Tag>{t(`audit.revealKinds.${record.kind}`)}</Tag>,
    },
    { title: t('audit.revealResource'), dataIndex: 'resource', search: false, ellipsis: true },
    { title: t('audit.entityType'), dataIndex: 'entityType', render: (_, record) => record.entity ?? '' },
    { title: t('audit.entityId'), dataIndex: 'entityId', copyable: true },
    { title: t('audit.revealFields'), key: 'fields', search: false, render: (_, record) => (record.fields ?? []).join(', ') },
    { title: t('audit.revealRows'), dataIndex: 'rowCount', search: false, align: 'right' },
  ]
  return (
    <ProTable<RevealRecord, RevealFilters>
      rowKey="revealId"
      columns={columns}
      pagination={{ defaultPageSize: 50 }}
      data-testid="audit-reveals"
      request={async ({ current = 1, pageSize = 50, revealedAt, actorId, entityType, entityId }) => {
        const query: Record<string, string | number | undefined> = {
          offset: (current - 1) * pageSize,
          limit: pageSize,
          actorId: actorId || undefined,
          entityType: entityType || undefined,
          entityId: entityId || undefined,
        }
        if (revealedAt) {
          query.from = new Date(revealedAt[0]).toISOString()
          query.to = new Date(revealedAt[1]).toISOString()
        }
        const page = await unwrap(api.GET('/api/audit/reveals', { params: { query } }))
        return { data: page.items ?? [], success: true, total: page.total ?? 0 }
      }}
    />
  )
}
