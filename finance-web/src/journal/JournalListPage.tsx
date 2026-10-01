import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, useAuth } from '@jabiz/admin'
import { Button, Input, Select, Space, Table, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { loadRegister, PERMISSIONS, type JournalStatus, type RegisterRow } from './api'
import { sumAmounts } from './grid'
import { journalPath, NEW_JOURNAL_PATH } from './paths'
import StatusTag from './StatusTag'

const STATUSES: JournalStatus[] = ['DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED']

function thisYear(): { from: string; to: string } {
  const year = new Date().getFullYear()
  return { from: `${year}-01-01`, to: `${year}-12-31` }
}

/**
 * The journal register (FIN-UI-004): entries with a posting date in a range, by state, sortable, with totals; each
 * row opens its entry. Read through the template finance.gl.journal_register (permission fin.journal.read).
 */
export default function JournalListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const navigate = useNavigate()
  const [range, setRange] = useState(thisYear)
  const [status, setStatus] = useState<JournalStatus | null>(null)

  const register = useQuery({
    queryKey: ['fin', 'register', range.from, range.to, status],
    queryFn: () => loadRegister({ ...range, status }),
    enabled: Boolean(range.from && range.to),
  })
  const rows = useMemo(() => register.data?.items ?? [], [register.data])
  const total = useMemo(() => sumAmounts(rows.map((row) => row.totalDebit)), [rows])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
          {t('journal.listTitle')}
        </Typography.Title>
        {can(PERMISSIONS.prepare) && (
          <Button type="primary" onClick={() => navigate(NEW_JOURNAL_PATH)} data-testid="new-journal">
            {t('journal.new')}
          </Button>
        )}
      </Space>
      <Space wrap>
        <label>
          {t('journal.from')}{' '}
          <Input type="date" value={range.from} aria-label={t('journal.from')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, from: e.target.value }))} />
        </label>
        <label>
          {t('journal.to')}{' '}
          <Input type="date" value={range.to} aria-label={t('journal.to')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, to: e.target.value }))} />
        </label>
        <Select<JournalStatus | null>
          aria-label={t('journal.status')}
          style={{ width: 180 }}
          value={status}
          onChange={setStatus}
          options={[
            { value: null, label: t('journal.allStatuses') },
            ...STATUSES.map((s) => ({ value: s, label: t(`journal.statuses.${s}`) })),
          ]}
        />
      </Space>
      <Table<RegisterRow>
        data-testid="journal-table"
        size="small"
        rowKey="journalId"
        loading={register.isLoading}
        dataSource={rows}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: register.error instanceof ApiError ? register.error.display : t('journal.none') }}
        onRow={(row) => ({ onDoubleClick: () => navigate(journalPath(row.journalId)) })}
        columns={[
          {
            title: t('journal.number'),
            dataIndex: 'journalNo',
            sorter: (a, b) => (a.journalNo ?? '').localeCompare(b.journalNo ?? ''),
            render: (value: string | null, row) => <Link to={journalPath(row.journalId)}>{value ?? t('journal.draftNo')}</Link>,
          },
          {
            title: t('journal.postingDate'),
            dataIndex: 'postingDate',
            defaultSortOrder: 'ascend',
            sorter: (a, b) => a.postingDate.localeCompare(b.postingDate),
            render: (value: string) => formatDate(value),
          },
          { title: t('journal.description'), dataIndex: 'description', ellipsis: true },
          { title: t('journal.source'), dataIndex: 'source', render: (value: string) => t(`journal.sources.${value}`, value) },
          {
            title: t('journal.status'),
            dataIndex: 'status',
            sorter: (a, b) => a.status.localeCompare(b.status),
            render: (value: JournalStatus) => <StatusTag status={value} />,
          },
          {
            title: t('journal.totalDebit'),
            dataIndex: 'totalDebit',
            align: 'right',
            sorter: (a, b) => Number(a.totalDebit) - Number(b.totalDebit),
            render: (value: number | string) => formatAmount(value, { scale: 2 }),
          },
          { title: t('journal.preparer'), dataIndex: 'preparer' },
          { title: t('journal.glNo'), dataIndex: 'glNo', sorter: (a, b) => (a.glNo ?? '').localeCompare(b.glNo ?? '') },
        ]}
        summary={() => (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0} colSpan={5}>
              {t('journal.registerTotal', { count: rows.length })}
            </Table.Summary.Cell>
            <Table.Summary.Cell index={5} align="right">
              <span data-testid="register-total">{formatAmount(total, { scale: 2 })}</span>
            </Table.Summary.Cell>
            <Table.Summary.Cell index={6} colSpan={2} />
          </Table.Summary.Row>
        )}
      />
    </Space>
  )
}
