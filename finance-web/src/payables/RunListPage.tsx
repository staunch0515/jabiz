import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, useAuth } from '@jabiz/admin'
import { Alert, Button, Input, Select, Space, Table, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { sum } from '../receivables/money'
import { loadRunRegister, PERMISSIONS, type RunRegisterRow, type RunStatus } from './api'
import { thisYear } from './dates'
import { NEW_RUN_PATH, runPath } from './paths'
import { RunStatusTag } from './StatusTags'

const STATUSES: RunStatus[] = ['DRAFT', 'SUBMITTED', 'APPROVED', 'RELEASED', 'CANCELLED']

/**
 * The payment run register (FIN-UI-004, FIN-AP-010): runs to pay on days in a range, by state, with who prepared,
 * approved and released each and their totals; each opens its run. Template finance.ap.payment_run_register.
 */
export default function RunListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const navigate = useNavigate()
  const [range, setRange] = useState(thisYear)
  const [status, setStatus] = useState<RunStatus | null>(null)
  const register = useQuery({
    queryKey: ['fin', 'runs', range.from, range.to, status],
    queryFn: () => loadRunRegister({ ...range, status }),
    enabled: Boolean(range.from && range.to),
  })
  const rows = useMemo(() => register.data?.items ?? [], [register.data])
  const total = useMemo(() => sum(rows.filter((r) => r.status !== 'CANCELLED').map((r) => r.total)), [rows])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('payables.runsTitle')}</Typography.Title>
        {can(PERMISSIONS.payment) && (
          <Button type="primary" onClick={() => navigate(NEW_RUN_PATH)} data-testid="new-run">{t('payables.newRun')}</Button>
        )}
      </Space>
      <Space wrap>
        <label>
          {t('payables.from')}{' '}
          <Input type="date" value={range.from} aria-label={t('payables.from')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, from: e.target.value }))} />
        </label>
        <label>
          {t('payables.to')}{' '}
          <Input type="date" value={range.to} aria-label={t('payables.to')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, to: e.target.value }))} />
        </label>
        <Select<RunStatus | null> aria-label={t('payables.status')} style={{ width: 220 }} value={status} onChange={setStatus}
          options={[{ value: null, label: t('payables.allStatuses') },
            ...STATUSES.map((s) => ({ value: s, label: t(`payables.runStatuses.${s}`) }))]} />
      </Space>
      {register.data?.total !== undefined && register.data.total > rows.length && (
        <Alert type="warning" showIcon message={t('payables.registerCapped', { shown: rows.length, total: register.data.total })} />
      )}
      <Table<RunRegisterRow>
        data-testid="run-table"
        size="small"
        rowKey="runId"
        loading={register.isLoading}
        dataSource={rows}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: register.error instanceof ApiError ? register.error.display : t('payables.noRuns') }}
        onRow={(row) => ({ onDoubleClick: () => navigate(runPath(row.runId)) })}
        columns={[
          { title: t('payables.runNo'), dataIndex: 'runNo', render: (v: string, row) => <Link to={runPath(row.runId)}>{v}</Link>,
            sorter: (a, b) => a.runNo.localeCompare(b.runNo) },
          { title: t('payables.paymentDate'), dataIndex: 'paymentDate', defaultSortOrder: 'ascend',
            sorter: (a, b) => a.paymentDate.localeCompare(b.paymentDate), render: (v: string) => formatDate(v) },
          { title: t('payables.method'), dataIndex: 'method', render: (v: string) => t(`payables.methods.${v}`, v) },
          { title: t('payables.bank'), dataIndex: 'bankCode' },
          { title: t('payables.description'), dataIndex: 'description', ellipsis: true },
          { title: t('payables.lineCount'), dataIndex: 'lineCount', align: 'right' },
          { title: t('payables.total'), dataIndex: 'total', align: 'right',
            render: (v: number | string | null) => formatAmount(v, { scale: 2 }) },
          { title: t('payables.status'), dataIndex: 'status', render: (v: RunStatus) => <RunStatusTag status={v} /> },
          { title: t('payables.preparedBy'), dataIndex: 'preparedBy' },
          { title: t('payables.approvedBy'), dataIndex: 'approvedBy' },
          { title: t('payables.releasedBy'), dataIndex: 'releasedBy' },
        ]}
        summary={() => (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0} colSpan={6}>{t('payables.runCount', { count: rows.length })}</Table.Summary.Cell>
            <Table.Summary.Cell index={6} align="right">
              <span data-testid="register-total">{formatAmount(total, { scale: 2 })}</span>
            </Table.Summary.Cell>
            <Table.Summary.Cell index={7} colSpan={4} />
          </Table.Summary.Row>
        )}
      />
    </Space>
  )
}
