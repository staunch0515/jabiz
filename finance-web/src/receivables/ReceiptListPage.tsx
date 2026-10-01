import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate, useAuth } from '@jabiz/admin'
import { Alert, Button, Input, Select, Space, Table, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import { loadReceiptRegister, PERMISSIONS, type ReceiptRegisterRow, type ReceiptStatus } from './api'
import { sum } from './money'
import { NEW_RECEIPT_PATH, receiptPath } from './paths'
import { ReceiptStatusTag } from './StatusTags'

function thisYear(): { from: string; to: string } {
  const year = new Date().getFullYear()
  return { from: `${year}-01-01`, to: `${year}-12-31` }
}

/**
 * The receipt register (FIN-UI-004): customer receipts dated in a range, by state and customer, sortable, with the
 * totals received and still unapplied; each row opens its receipt. Read through finance.ar.receipt_register.
 */
export default function ReceiptListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const navigate = useNavigate()
  const [range, setRange] = useState(thisYear)
  const [status, setStatus] = useState<ReceiptStatus | null>(null)
  const [customerCode, setCustomerCode] = useState('')

  const register = useQuery({
    queryKey: ['fin', 'receipts', range.from, range.to, status, customerCode],
    queryFn: () => loadReceiptRegister({ ...range, status, customerCode: customerCode.trim() }),
    enabled: Boolean(range.from && range.to),
  })
  const rows = useMemo(() => register.data?.items ?? [], [register.data])
  const total = useMemo(() => sum(rows.map((row) => row.amount)), [rows])
  const unappliedTotal = useMemo(() => sum(rows.map((row) => row.unappliedAmount)), [rows])
  const money = (value: number | string) => formatAmount(value, { scale: 2 })

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space align="center" style={{ justifyContent: 'space-between', width: '100%' }}>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">
          {t('receivables.receiptsTitle')}
        </Typography.Title>
        {can(PERMISSIONS.receipt) && (
          <Button type="primary" onClick={() => navigate(NEW_RECEIPT_PATH)} data-testid="new-receipt">
            {t('receivables.newReceipt')}
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
        <Select<ReceiptStatus | null>
          aria-label={t('receivables.status')}
          style={{ width: 160 }}
          value={status}
          onChange={setStatus}
          options={[
            { value: null, label: t('receivables.anyStatus') },
            { value: 'POSTED', label: t('receivables.receipt.statuses.POSTED') },
            { value: 'VOID', label: t('receivables.receipt.statuses.VOID') },
          ]}
        />
      </Space>
      {register.data?.total !== undefined && register.data.total > rows.length && (
        <Alert type="warning" showIcon data-testid="register-capped"
          message={t('receivables.registerCapped', { shown: rows.length, total: register.data.total })} />
      )}
      <Table<ReceiptRegisterRow>
        data-testid="receipt-table"
        size="small"
        rowKey="receiptId"
        loading={register.isLoading}
        dataSource={rows}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: register.error instanceof ApiError ? register.error.display : t('receivables.noReceipts') }}
        onRow={(row) => ({ onDoubleClick: () => navigate(receiptPath(row.receiptId)) })}
        columns={[
          {
            title: t('receivables.number'),
            dataIndex: 'receiptNo',
            sorter: (a, b) => (a.receiptNo ?? '').localeCompare(b.receiptNo ?? ''),
            render: (value: string | null, row) => <Link to={receiptPath(row.receiptId)}>{value ?? ''}</Link>,
          },
          {
            title: t('receivables.date'),
            dataIndex: 'receiptDate',
            defaultSortOrder: 'ascend',
            sorter: (a, b) => a.receiptDate.localeCompare(b.receiptDate),
            render: (value: string) => formatDate(value),
          },
          { title: t('receivables.customer'), dataIndex: 'customerCode',
            sorter: (a, b) => a.customerCode.localeCompare(b.customerCode) },
          { title: t('receivables.customerName'), dataIndex: 'customerName', ellipsis: true },
          { title: t('receivables.receipt.method'), dataIndex: 'method',
            render: (value: string) => t(`receivables.receipt.methods.${value}`, value) },
          { title: t('receivables.reference'), dataIndex: 'reference', ellipsis: true },
          { title: t('receivables.receipt.bankAccount'), dataIndex: 'bankAccount' },
          {
            title: t('receivables.receipt.amount'),
            dataIndex: 'amount',
            align: 'right',
            sorter: (a, b) => Number(a.amount) - Number(b.amount),
            render: money,
          },
          {
            title: t('receivables.receipt.unapplied'),
            dataIndex: 'unappliedAmount',
            align: 'right',
            sorter: (a, b) => Number(a.unappliedAmount) - Number(b.unappliedAmount),
            render: money,
          },
          { title: t('receivables.status'), dataIndex: 'status',
            render: (value: ReceiptStatus) => <ReceiptStatusTag status={value} /> },
        ]}
        summary={() => (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0} colSpan={7}>{t('receivables.receiptCount', { count: rows.length })}</Table.Summary.Cell>
            <Table.Summary.Cell index={7} align="right"><span data-testid="register-total">{money(total)}</span></Table.Summary.Cell>
            <Table.Summary.Cell index={8} align="right"><span data-testid="register-unapplied">{money(unappliedTotal)}</span></Table.Summary.Cell>
            <Table.Summary.Cell index={9} />
          </Table.Summary.Row>
        )}
      />
    </Space>
  )
}
