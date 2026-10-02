import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, useAuth } from '@jabiz/admin'
import { Alert, Button, Input, Select, Space, Table, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { sum } from '../receivables/money'
import {
  loadBillRegister,
  PERMISSIONS,
  type Approval,
  type BillKind,
  type BillRegisterRow,
  type BillStatus,
} from './api'
import { thisYear } from './dates'
import { billPath, NEW_BILL_PATH, NEW_CREDIT_PATH } from './paths'
import { ApprovalTag, BillStatusTag } from './StatusTags'

const STATUSES: BillStatus[] = ['DRAFT', 'POSTED', 'VOID']
const KINDS: BillKind[] = ['BILL', 'CREDIT']
const APPROVALS: Approval[] = ['PENDING', 'APPROVED', 'REJECTED', 'NOT_REQUIRED']

const byText = (a?: string | null, b?: string | null) => (a ?? '').localeCompare(b ?? '')
const byAmount = (a: unknown, b: unknown) => Number(a ?? 0) - Number(b ?? 0)
const money = (value: number | string | null) => formatAmount(value, { scale: 2 })

/**
 * The bill register (FIN-UI-004): bills and vendor credits dated in a range, by state, kind, approval and vendor,
 * sortable, with totals (credits less) that add up to what was posted, and what is still open; each row opens its
 * document. Read through the template finance.ap.bill_register (permission fin.ap.read).
 */
export default function BillListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const navigate = useNavigate()
  const [range, setRange] = useState(thisYear)
  const [status, setStatus] = useState<BillStatus | null>(null)
  const [kind, setKind] = useState<BillKind | null>(null)
  const [approval, setApproval] = useState<Approval | null>(null)
  const [vendorCode, setVendorCode] = useState('')

  const register = useQuery({
    queryKey: ['fin', 'bills', range.from, range.to, status, kind, approval, vendorCode],
    queryFn: () => loadBillRegister({ ...range, status, kind, approval, vendorCode: vendorCode.trim() }),
    enabled: Boolean(range.from && range.to),
  })
  const rows = useMemo(() => register.data?.items ?? [], [register.data])
  const total = useMemo(() => sum(rows.map((row) => row.total)), [rows])
  const open = useMemo(() => sum(rows.map((row) => row.openAmount)), [rows])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('payables.billsTitle')}</Typography.Title>
        {can(PERMISSIONS.prepare) && (
          <Space>
            <Button onClick={() => navigate(NEW_CREDIT_PATH)} data-testid="new-credit">{t('payables.newCredit')}</Button>
            <Button type="primary" onClick={() => navigate(NEW_BILL_PATH)} data-testid="new-bill">{t('payables.newBill')}</Button>
          </Space>
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
        <Input value={vendorCode} aria-label={t('payables.vendor')} placeholder={t('payables.anyVendor')}
          style={{ width: 150 }} onChange={(e) => setVendorCode(e.target.value.toUpperCase())} />
        <Select<BillStatus | null> aria-label={t('payables.status')} style={{ width: 200 }} value={status}
          onChange={setStatus}
          options={[{ value: null, label: t('payables.allStatuses') },
            ...STATUSES.map((s) => ({ value: s, label: t(`payables.statuses.${s}`) }))]} />
        <Select<BillKind | null> aria-label={t('payables.kind')} style={{ width: 180 }} value={kind} onChange={setKind}
          options={[{ value: null, label: t('payables.allKinds') },
            ...KINDS.map((k) => ({ value: k, label: t(`payables.kinds.${k}`) }))]} />
        <Select<Approval | null> aria-label={t('payables.approval')} style={{ width: 200 }} value={approval}
          onChange={setApproval}
          options={[{ value: null, label: t('payables.anyApproval') },
            ...APPROVALS.map((a) => ({ value: a, label: t(`payables.approvals.${a}`) }))]} />
      </Space>
      {register.data?.total !== undefined && register.data.total > rows.length && (
        <Alert type="warning" showIcon data-testid="register-capped"
          message={t('payables.registerCapped', { shown: rows.length, total: register.data.total })} />
      )}
      <Table<BillRegisterRow>
        data-testid="bill-table"
        size="small"
        rowKey="billId"
        loading={register.isLoading}
        dataSource={rows}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: register.error instanceof ApiError ? register.error.display : t('payables.noBills') }}
        onRow={(row) => ({ onDoubleClick: () => navigate(billPath(row.billId)) })}
        columns={[
          {
            title: t('payables.number'),
            dataIndex: 'billNo',
            sorter: (a, b) => byText(a.billNo, b.billNo),
            render: (value: string | null, row) => <Link to={billPath(row.billId)}>{value ?? t('payables.draftNo')}</Link>,
          },
          { title: t('payables.kind'), dataIndex: 'kind', render: (value: BillKind) => t(`payables.kinds.${value}`) },
          { title: t('payables.vendor'), dataIndex: 'vendorCode', sorter: (a, b) => byText(a.vendorCode, b.vendorCode) },
          { title: t('payables.vendorName'), dataIndex: 'vendorName', ellipsis: true },
          { title: t('payables.vendorInvoiceNo'), dataIndex: 'vendorInvoiceNo' },
          {
            title: t('payables.date'),
            dataIndex: 'invoiceDate',
            defaultSortOrder: 'ascend',
            sorter: (a, b) => a.invoiceDate.localeCompare(b.invoiceDate),
            render: (value: string) => formatDate(value),
          },
          {
            title: t('payables.dueDate'),
            dataIndex: 'dueDate',
            sorter: (a, b) => byText(a.dueDate, b.dueDate),
            render: (value: string | null) => (value ? formatDate(value) : ''),
          },
          { title: t('payables.total'), dataIndex: 'total', align: 'right', sorter: (a, b) => byAmount(a.total, b.total),
            render: money },
          { title: t('payables.open'), dataIndex: 'openAmount', align: 'right',
            sorter: (a, b) => byAmount(a.openAmount, b.openAmount), render: money },
          {
            title: t('payables.status'),
            dataIndex: 'status',
            render: (value: BillStatus, row) => <><BillStatusTag status={value} /><ApprovalTag approval={row.approval} /></>,
          },
          { title: t('payables.glNo'), dataIndex: 'glNo', sorter: (a, b) => byText(a.glNo, b.glNo) },
        ]}
        summary={() => (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0} colSpan={7}>{t('payables.registerCount', { count: rows.length })}</Table.Summary.Cell>
            <Table.Summary.Cell index={7} align="right">
              <span data-testid="register-total">{money(total)}</span>
            </Table.Summary.Cell>
            <Table.Summary.Cell index={8} align="right">
              <span data-testid="register-open">{money(open)}</span>
            </Table.Summary.Cell>
            <Table.Summary.Cell index={9} colSpan={2} />
          </Table.Summary.Row>
        )}
      />
    </Space>
  )
}
