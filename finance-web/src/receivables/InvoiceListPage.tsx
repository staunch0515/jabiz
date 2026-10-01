import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, useAuth } from '@jabiz/admin'
import { Alert, Button, Input, Select, Space, Table, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import {
  loadInvoiceRegister,
  PERMISSIONS,
  type InvoiceKind,
  type InvoiceRegisterRow,
  type InvoiceStatus,
} from './api'
import { sum } from './money'
import { invoicePath, NEW_INVOICE_PATH } from './paths'
import { InvoiceStatusTag } from './StatusTags'

const STATUSES: InvoiceStatus[] = ['DRAFT', 'POSTED', 'VOID', 'WRITTEN_OFF']
const KINDS: InvoiceKind[] = ['INVOICE', 'CREDIT_MEMO']

function thisYear(): { from: string; to: string } {
  const year = new Date().getFullYear()
  return { from: `${year}-01-01`, to: `${year}-12-31` }
}

const byText = (a?: string | null, b?: string | null) => (a ?? '').localeCompare(b ?? '')
const byAmount = (a: unknown, b: unknown) => Number(a ?? 0) - Number(b ?? 0)

/**
 * The invoice register (FIN-UI-004): invoices and credit memos dated in a range, by state, kind and customer,
 * sortable, with totals in US dollars (credit memos less) that add up to what was posted; each row opens its
 * document. Read through the template finance.ar.invoice_register (permission fin.ar.read).
 */
export default function InvoiceListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const navigate = useNavigate()
  const [range, setRange] = useState(thisYear)
  const [status, setStatus] = useState<InvoiceStatus | null>(null)
  const [kind, setKind] = useState<InvoiceKind | null>(null)
  const [customerCode, setCustomerCode] = useState('')

  const register = useQuery({
    queryKey: ['fin', 'invoices', range.from, range.to, status, kind, customerCode],
    queryFn: () => loadInvoiceRegister({ ...range, status, kind, customerCode: customerCode.trim() }),
    enabled: Boolean(range.from && range.to),
  })
  const rows = useMemo(() => register.data?.items ?? [], [register.data])
  const totalUsd = useMemo(() => sum(rows.map((row) => row.totalUsd)), [rows])
  const openUsd = useMemo(() => sum(rows.map((row) => row.openAmountUsd)), [rows])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
          {t('receivables.invoicesTitle')}
        </Typography.Title>
        {can(PERMISSIONS.prepare) && (
          <Button type="primary" onClick={() => navigate(NEW_INVOICE_PATH)} data-testid="new-invoice">
            {t('receivables.newInvoice')}
          </Button>
        )}
      </Space>
      <Space wrap>
        <label>
          {t('receivables.from')}{' '}
          <Input type="date" value={range.from} aria-label={t('receivables.from')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, from: e.target.value }))} />
        </label>
        <label>
          {t('receivables.to')}{' '}
          <Input type="date" value={range.to} aria-label={t('receivables.to')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, to: e.target.value }))} />
        </label>
        <Input value={customerCode} aria-label={t('receivables.customer')} placeholder={t('receivables.anyCustomer')}
          style={{ width: 150 }} onChange={(e) => setCustomerCode(e.target.value.toUpperCase())} />
        <Select<InvoiceStatus | null>
          aria-label={t('receivables.status')}
          style={{ width: 230 }}
          value={status}
          onChange={setStatus}
          options={[
            { value: null, label: t('receivables.allStatuses') },
            ...STATUSES.map((s) => ({ value: s, label: t(`receivables.statuses.${s}`) })),
          ]}
        />
        <Select<InvoiceKind | null>
          aria-label={t('receivables.kind')}
          style={{ width: 230 }}
          value={kind}
          onChange={setKind}
          options={[
            { value: null, label: t('receivables.allKinds') },
            ...KINDS.map((k) => ({ value: k, label: t(`receivables.kinds.${k}`) })),
          ]}
        />
      </Space>
      {register.data?.total !== undefined && register.data.total > rows.length && (
        <Alert type="warning" showIcon data-testid="register-capped"
          message={t('receivables.registerCapped', { shown: rows.length, total: register.data.total })} />
      )}
      <Table<InvoiceRegisterRow>
        data-testid="invoice-table"
        size="small"
        rowKey="invoiceId"
        loading={register.isLoading}
        dataSource={rows}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: register.error instanceof ApiError ? register.error.display : t('receivables.noInvoices') }}
        onRow={(row) => ({ onDoubleClick: () => navigate(invoicePath(row.invoiceId)) })}
        columns={[
          {
            title: t('receivables.number'),
            dataIndex: 'invoiceNo',
            sorter: (a, b) => byText(a.invoiceNo, b.invoiceNo),
            render: (value: string | null, row) => (
              <Link to={invoicePath(row.invoiceId)}>{value ?? t('receivables.draftNo')}</Link>
            ),
          },
          { title: t('receivables.kind'), dataIndex: 'kind', render: (value: InvoiceKind) => t(`receivables.kinds.${value}`) },
          {
            title: t('receivables.date'),
            dataIndex: 'invoiceDate',
            defaultSortOrder: 'ascend',
            sorter: (a, b) => a.invoiceDate.localeCompare(b.invoiceDate),
            render: (value: string) => formatDate(value),
          },
          {
            title: t('receivables.dueDate'),
            dataIndex: 'dueDate',
            sorter: (a, b) => byText(a.dueDate, b.dueDate),
            render: (value: string | null) => (value ? formatDate(value) : ''),
          },
          { title: t('receivables.customer'), dataIndex: 'customerCode', sorter: (a, b) => byText(a.customerCode, b.customerCode) },
          { title: t('receivables.customerName'), dataIndex: 'customerName', ellipsis: true },
          { title: t('receivables.reference'), dataIndex: 'reference', ellipsis: true },
          { title: t('receivables.currency'), dataIndex: 'currency' },
          { title: t('receivables.total'), dataIndex: 'total', align: 'right',
            render: (value: number | string | null) => formatAmount(value, { scale: 2 }) },
          {
            title: t('receivables.totalUsd'),
            dataIndex: 'totalUsd',
            align: 'right',
            sorter: (a, b) => byAmount(a.totalUsd, b.totalUsd),
            render: (value: number | string | null) => formatAmount(value, { scale: 2 }),
          },
          {
            title: t('receivables.openUsd'),
            dataIndex: 'openAmountUsd',
            align: 'right',
            sorter: (a, b) => byAmount(a.openAmountUsd, b.openAmountUsd),
            render: (value: number | string | null) => formatAmount(value, { scale: 2 }),
          },
          {
            title: t('receivables.status'),
            dataIndex: 'status',
            sorter: (a, b) => a.status.localeCompare(b.status),
            render: (value: InvoiceStatus) => <InvoiceStatusTag status={value} />,
          },
          { title: t('receivables.glNo'), dataIndex: 'glNo', sorter: (a, b) => byText(a.glNo, b.glNo) },
        ]}
        summary={() => (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0} colSpan={9}>
              {t('receivables.registerCount', { count: rows.length })}
            </Table.Summary.Cell>
            <Table.Summary.Cell index={9} align="right">
              <span data-testid="register-total">{formatAmount(totalUsd, { scale: 2 })}</span>
            </Table.Summary.Cell>
            <Table.Summary.Cell index={10} align="right">
              <span data-testid="register-open">{formatAmount(openUsd, { scale: 2 })}</span>
            </Table.Summary.Cell>
            <Table.Summary.Cell index={11} colSpan={2} />
          </Table.Summary.Row>
        )}
      />
    </Space>
  )
}
